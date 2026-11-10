package com.rendyhd.vicu.util

import com.rendyhd.vicu.domain.model.Task
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * A custom list's rule: a condition in the filter language of Vikunja's saved filters
 * (docs/cross-app-semantics-v1.md, section 3.5). Desktop has the same in
 * `src/shared/custom-list-rule.ts`; both run the `customListRule` vectors.
 *
 * [parse] reads the text into a tree without judging the fields, so an import can remove the terms
 * Vicu does not support ([withoutTerms]) before [check] decides whether the rule is valid.
 * [compile] turns a valid rule into a predicate for one moment and zone.
 */
object CustomListRule {

    const val MAX_BYTES = 16 * 1024
    const val MAX_DEPTH = 100
    const val MAX_BUSINESS_DAYS = 10_000

    enum class Join { AND, OR }

    enum class Op(val text: String) {
        EQ("="), NEQ("!="), GT(">"), GTE(">="), LT("<"), LTE("<="), LIKE("like"), IN("in"), NOT_IN("not in");

        val isRange: Boolean get() = this == GT || this == GTE || this == LT || this == LTE
        val isList: Boolean get() = this == IN || this == NOT_IN
    }

    sealed interface Node

    /**
     * `field op value`. [value] is the text as written (unquoted, escapes resolved); [valueStart]
     * and [valueEnd] are its place in the source, quotes included, for editors that rewrite it.
     */
    data class Term(
        val field: String,
        val op: Op,
        val value: String,
        val quoted: Boolean,
        val start: Int,
        val valueStart: Int,
        val valueEnd: Int,
    ) : Node {
        /** The items of an `in` list, or of a username field, which the server always splits. */
        fun items(): List<String> = value.split(',').map { it.trim() }
    }

    data class Group(val items: List<Item>) : Node

    /** One element of a sequence and the join in front of it (ignored for the first). */
    data class Item(val join: Join, val node: Node)

    enum class ErrorCode { SYNTAX, UNKNOWN_FIELD, UNSUPPORTED_FIELD, BAD_OPERATOR, BAD_VALUE, TOO_COMPLEX }

    /** What is wrong, in words for the editor, and where ([start] until [end] in the text). */
    data class RuleError(val code: ErrorCode, val message: String, val start: Int, val end: Int)

    sealed interface Parsed {
        data class Ok(val root: Group) : Parsed
        data class Error(val error: RuleError) : Parsed
    }

    // --- Fields ---------------------------------------------------------------------------

    enum class Kind { BOOL, WHOLE, NUMBER, DATE, TEXT, LABELS, USERS, REMINDERS, CREATOR }

    /** The fields of section 3.5 and what kind of value each holds. */
    val FIELDS: Map<String, Kind> = linkedMapOf(
        "done" to Kind.BOOL,
        "priority" to Kind.WHOLE,
        "due_date" to Kind.DATE,
        "start_date" to Kind.DATE,
        "end_date" to Kind.DATE,
        "done_at" to Kind.DATE,
        "created" to Kind.DATE,
        "updated" to Kind.DATE,
        "labels" to Kind.LABELS,
        "project" to Kind.WHOLE,
        "assignees" to Kind.USERS,
        "created_by" to Kind.CREATOR,
        "reminders" to Kind.REMINDERS,
        "percent_done" to Kind.NUMBER,
        "title" to Kind.TEXT,
        "description" to Kind.TEXT,
        "hex_color" to Kind.TEXT,
        "repeat_after" to Kind.WHOLE,
        "id" to Kind.WHOLE,
        "index" to Kind.WHOLE,
        "project_id" to Kind.WHOLE,
        "created_by_id" to Kind.WHOLE,
    )

    /** Valid on the server but not here; an import removes them (section 3.7). */
    val SERVER_ONLY_FIELDS: Set<String> = setOf("bucket_id", "position", "uid")

    private fun isMany(kind: Kind) = kind == Kind.LABELS || kind == Kind.USERS || kind == Kind.REMINDERS

    // --- Parsing --------------------------------------------------------------------------

    private class Syntax(val error: RuleError) : Exception(error.message)

    /** Reads [text]. A blank text is an empty rule (an [Parsed.Ok] with no items). */
    fun parse(text: String): Parsed {
        if (text.encodeToByteArray().size > MAX_BYTES) {
            return Parsed.Error(RuleError(ErrorCode.TOO_COMPLEX, "The rule is too long.", 0, text.length))
        }
        return try {
            val reader = Reader(text)
            val root = reader.sequence(depth = 0, closing = false)
            Parsed.Ok(root)
        } catch (e: Syntax) {
            Parsed.Error(e.error)
        }
    }

    private class Reader(val s: String) {
        var i = 0

        fun fail(message: String, start: Int = i, end: Int = (start + 1).coerceAtMost(s.length)): Nothing =
            throw Syntax(RuleError(ErrorCode.SYNTAX, message, start, maxOf(start, end)))

        fun skipSpace() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun sequence(depth: Int, closing: Boolean): Group {
            if (depth > MAX_DEPTH) {
                throw Syntax(RuleError(ErrorCode.TOO_COMPLEX, "The rule has too many groups inside each other.", i, i))
            }
            val items = mutableListOf<Item>()
            // The join read after the last item, waiting for the next one.
            var join: Join? = null
            while (true) {
                skipSpace()
                if (i >= s.length) {
                    if (closing) fail("A group is missing its closing parenthesis.", s.length, s.length)
                    if (join != null) fail("The rule ends after && or ||.", s.length - 2, s.length)
                    return Group(items)
                }
                if (s[i] == ')') {
                    if (!closing) fail("There is a closing parenthesis without an opening one.")
                    if (items.isEmpty()) fail("A group is empty.")
                    if (join != null) fail("A group ends after && or ||.")
                    i++
                    return Group(items)
                }
                val isJoin = s.startsWith("&&", i) || s.startsWith("||", i)
                if (items.isNotEmpty() && join == null) {
                    if (!isJoin) fail("Expected && or || here.")
                    join = if (s[i] == '&') Join.AND else Join.OR
                    i += 2
                    continue
                }
                if (isJoin) {
                    fail(if (items.isEmpty()) "The rule starts with && or ||." else "Two joins follow each other.", i, i + 2)
                }
                val node: Node = if (s[i] == '(') {
                    i++
                    sequence(depth + 1, closing = true)
                } else {
                    term()
                }
                items += Item(join ?: Join.AND, node)
                join = null
            }
        }

        private fun isWordChar(c: Char) = c.isLetterOrDigit() || c == '_'

        fun term(): Term {
            val start = i
            while (i < s.length && isWordChar(s[i])) i++
            if (i == start) fail("Expected a field name here.")
            val field = s.substring(start, i)
            skipSpace()
            val op = operator() ?: fail("Expected an operator after \"$field\".", i)
            skipSpace()
            val valueStart = i
            if (i >= s.length) fail("\"$field ${op.text}\" needs a value.", start, i)
            val c = s[i]
            if (c == '\'' || c == '"') {
                val out = StringBuilder()
                i++
                while (true) {
                    if (i >= s.length) fail("A quoted value is missing its closing quote.", valueStart, s.length)
                    val ch = s[i]
                    if (ch == '\\' && i + 1 < s.length) {
                        out.append(s[i + 1])
                        i += 2
                        continue
                    }
                    if (ch == c) {
                        i++
                        break
                    }
                    out.append(ch)
                    i++
                }
                return Term(field, op, out.toString(), quoted = true, start = start, valueStart = valueStart, valueEnd = i)
            }
            while (i < s.length && s[i] != '&' && s[i] != '|' && s[i] != '(' && s[i] != ')') i++
            val raw = s.substring(valueStart, i)
            val value = raw.trim()
            if (value.isEmpty()) fail("\"$field ${op.text}\" needs a value.", start, i)
            val end = valueStart + raw.trimEnd().length
            return Term(field, op, value, quoted = false, start = start, valueStart = valueStart, valueEnd = end)
        }

        private fun wordAt(word: String): Boolean {
            if (!s.startsWith(word, i)) return false
            val after = i + word.length
            return after >= s.length || s[after].isWhitespace() || s[after] == '\'' || s[after] == '"'
        }

        fun operator(): Op? {
            if (s.startsWith("not", i)) {
                var j = i + 3
                if (j < s.length && s[j].isWhitespace()) {
                    while (j < s.length && s[j].isWhitespace()) j++
                    if (s.startsWith("in", j)) {
                        val after = j + 2
                        if (after >= s.length || s[after].isWhitespace() || s[after] == '\'' || s[after] == '"') {
                            i = after
                            return Op.NOT_IN
                        }
                    }
                }
            }
            if (wordAt("like")) { i += 4; return Op.LIKE }
            if (wordAt("in")) { i += 2; return Op.IN }
            for (op in listOf(Op.GTE, Op.LTE, Op.NEQ, Op.EQ, Op.GT, Op.LT)) {
                if (s.startsWith(op.text, i)) {
                    i += op.text.length
                    return op
                }
            }
            return null
        }
    }

    // --- Checking -------------------------------------------------------------------------

    /** The first problem in [root], or null when every term is one section 3.5 allows. */
    fun check(root: Group): RuleError? {
        var businessDays = 0
        fun visit(group: Group): RuleError? {
            for (item in group.items) {
                when (val node = item.node) {
                    is Group -> visit(node)?.let { return it }
                    is Term -> {
                        checkTerm(node)?.let { return it }
                        businessDays += businessDayCount(node)
                        if (businessDays > MAX_BUSINESS_DAYS) {
                            return RuleError(ErrorCode.TOO_COMPLEX, "The rule adds too many business days.", node.start, node.valueEnd)
                        }
                    }
                }
            }
            return null
        }
        return visit(root)
    }

    /** Parses and checks [text]: the error, or null when the rule is valid (or empty). */
    fun validate(text: String): RuleError? = when (val parsed = parse(text)) {
        is Parsed.Error -> parsed.error
        is Parsed.Ok -> check(parsed.root)
    }

    private val BUSINESS = Regex("""[+-](\d*)b""")

    private fun businessDayCount(term: Term): Int {
        val kind = FIELDS[term.field] ?: return 0
        if (kind != Kind.DATE && kind != Kind.REMINDERS) return 0
        return BUSINESS.findAll(term.value).sumOf { m ->
            val digits = m.groupValues[1]
            if (digits.isEmpty()) 1 else digits.toIntOrNull()?.coerceAtMost(MAX_BUSINESS_DAYS + 1) ?: (MAX_BUSINESS_DAYS + 1)
        }
    }

    private fun checkTerm(term: Term): RuleError? {
        fun error(code: ErrorCode, message: String, start: Int = term.start, end: Int = term.valueEnd) =
            RuleError(code, message, start, end)
        val field = term.field
        val kind = FIELDS[field]
        if (kind == null) {
            if (field in SERVER_ONLY_FIELDS) {
                return error(ErrorCode.UNSUPPORTED_FIELD, "Vicu can't check \"$field\".", term.start, term.start + field.length)
            }
            val near = FIELDS.keys.firstOrNull { it.equals(field, ignoreCase = true) } ?: closestField(field)
            val hint = near?.let { " Did you mean $it?" } ?: ""
            return error(ErrorCode.UNKNOWN_FIELD, "\"$field\" is not a field.$hint", term.start, term.start + field.length)
        }
        val op = term.op
        if (op == Op.LIKE && kind != Kind.TEXT && kind != Kind.USERS && kind != Kind.CREATOR) {
            return error(ErrorCode.BAD_OPERATOR, "\"like\" only works on text, assignees and created_by.")
        }
        if (kind == Kind.CREATOR && op.isRange) {
            return error(ErrorCode.BAD_OPERATOR, "created_by takes =, !=, in, not in or like.")
        }
        if (op == Op.LIKE) return null
        val values: List<String> = if (op.isList || kind == Kind.USERS || kind == Kind.CREATOR) term.items() else listOf(term.value)
        val bad = when (kind) {
            Kind.USERS, Kind.CREATOR -> if (values.none { it.isNotEmpty() }) term.value else null
            Kind.TEXT -> null
            else -> values.firstOrNull { !valueReads(kind, it) }
        }
        if (bad != null) {
            val what = when (kind) {
                Kind.BOOL -> "true or false"
                Kind.WHOLE, Kind.LABELS -> "a whole number"
                Kind.NUMBER -> "a number"
                Kind.DATE, Kind.REMINDERS -> "a date, like 2026-10-06 or now+7d"
                else -> "a value"
            }
            return error(ErrorCode.BAD_VALUE, "\"$bad\" is not $what.", term.valueStart, term.valueEnd)
        }
        return null
    }

    private fun closestField(field: String): String? {
        val lower = field.lowercase()
        return FIELDS.keys.minByOrNull { distance(it, lower) }?.takeIf { distance(it, lower) <= 2 }
    }

    private fun distance(a: String, b: String): Int {
        val prev = IntArray(b.length + 1) { it }
        val cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            cur.copyInto(prev)
        }
        return prev[b.length]
    }

    private val WHOLE = Regex("""[+-]?\d+""")
    private val NUMBER = Regex("""[+-]?(\d+(\.\d*)?|\.\d+)([eE][+-]?\d+)?""")

    private fun valueReads(kind: Kind, text: String): Boolean = when (kind) {
        Kind.BOOL -> parseBool(text) != null
        Kind.WHOLE, Kind.LABELS -> WHOLE.matches(text) && text.trimStart('+', '-').length <= 18
        Kind.NUMBER -> NUMBER.matches(text)
        // Any moment and zone will do to see whether it reads.
        Kind.DATE, Kind.REMINDERS -> parseDate(text, Instant.fromEpochSeconds(0), TimeZone.UTC) != null
        else -> true
    }

    private fun parseBool(text: String): Boolean? = when (text.lowercase()) {
        "1", "t", "true" -> true
        "0", "f", "false" -> false
        else -> null
    }

    private fun parseWhole(text: String): Long? = text.trimStart('+').toLongOrNull()

    // --- Dates ----------------------------------------------------------------------------

    private val FIXED_DAY = Regex("""(\d{1,4})-(\d{1,2})-(\d{1,2})""")
    private val FIXED_MINUTE = Regex("""(\d{4})-(\d{2})-(\d{2}) (\d{2}):(\d{2})""")
    private val RFC3339 = Regex("""\d{4}-\d{2}-\d{2}[Tt]\d{2}:\d{2}:\d{2}(\.\d+)?([Zz]|[+-]\d{2}:\d{2})""")

    /** A fixed date of section 3.5, or null. */
    private fun parseFixed(text: String, zone: TimeZone): Instant? {
        val t = text.trim()
        if (RFC3339.matches(t)) return runCatching { Instant.parse(t.replace('t', 'T').replace('z', 'Z')) }.getOrNull()
        FIXED_MINUTE.matchEntire(t)?.let { m ->
            val (y, mo, d, h, mi) = m.destructured
            return runCatching {
                LocalDateTime(y.toInt(), mo.toInt(), d.toInt(), h.toInt(), mi.toInt()).toInstant(zone)
            }.getOrNull()
        }
        FIXED_DAY.matchEntire(t)?.let { m ->
            val (y, mo, d) = m.destructured
            return runCatching { LocalDate(y.toInt(), mo.toInt(), d.toInt()).atStartOfDayIn(zone) }.getOrNull()
        }
        return null
    }

    /**
     * A date value of section 3.5 at the moment [now] in [zone]: a fixed date or date math. Null
     * when it does not read.
     */
    fun parseDate(text: String, now: Instant, zone: TimeZone): Instant? {
        val t = text.trim()
        val (anchor, steps) = when {
            t.startsWith("now") -> now to t.substring(3)
            t.contains("||") -> {
                val at = t.indexOf("||")
                (parseFixed(t.substring(0, at), zone) ?: return null) to t.substring(at + 2)
            }
            else -> return parseFixed(t, zone)
        }
        var moment = anchor
        var i = 0
        while (i < steps.length) {
            val sign = steps[i]
            if (sign == '/') {
                val unit = steps.getOrNull(i + 1) ?: return null
                if (unit == 'b') return null
                moment = roundDown(moment, unit, zone) ?: return null
                i += 2
                continue
            }
            if (sign != '+' && sign != '-') return null
            var j = i + 1
            while (j < steps.length && steps[j].isDigit()) j++
            val amount = if (j == i + 1) 1L else steps.substring(i + 1, j).toLongOrNull() ?: return null
            val unit = steps.getOrNull(j) ?: return null
            moment = add(moment, if (sign == '-') -amount else amount, unit, zone) ?: return null
            i = j + 1
        }
        return moment
    }

    private fun add(moment: Instant, amount: Long, unit: Char, zone: TimeZone): Instant? = when (unit) {
        'y' -> moment.plus(amount * 12, DateTimeUnit.MONTH, zone)
        'M' -> moment.plus(amount, DateTimeUnit.MONTH, zone)
        'w' -> moment.plus(amount * 7, DateTimeUnit.DAY, zone)
        'd' -> moment.plus(amount, DateTimeUnit.DAY, zone)
        'h', 'H' -> moment + amount.hours
        'm' -> moment + amount.minutes
        's' -> moment + amount.seconds
        'b' -> {
            if (kotlin.math.abs(amount) > MAX_BUSINESS_DAYS) return null
            var m = moment
            val step = if (amount < 0) -1L else 1L
            repeat(kotlin.math.abs(amount).toInt()) {
                m = m.plus(step, DateTimeUnit.DAY, zone)
                while (m.toLocalDateTime(zone).dayOfWeek.let { it == DayOfWeek.SATURDAY || it == DayOfWeek.SUNDAY }) {
                    m = m.plus(step, DateTimeUnit.DAY, zone)
                }
            }
            m
        }
        else -> null
    }

    private fun roundDown(moment: Instant, unit: Char, zone: TimeZone): Instant? {
        val local = moment.toLocalDateTime(zone)
        val date = local.date
        return when (unit) {
            'y' -> LocalDate(date.year, 1, 1).atStartOfDayIn(zone)
            'M' -> LocalDate(date.year, date.month, 1).atStartOfDayIn(zone)
            'w' -> date.minus(date.dayOfWeek.ordinal, DateTimeUnit.DAY).atStartOfDayIn(zone)
            'd' -> date.atStartOfDayIn(zone)
            'h', 'H' -> LocalDateTime(date, LocalTime(local.hour, 0)).toInstant(zone)
            'm' -> LocalDateTime(date, LocalTime(local.hour, local.minute)).toInstant(zone)
            's' -> LocalDateTime(date, LocalTime(local.hour, local.minute, local.second)).toInstant(zone)
            else -> null
        }
    }

    // --- Evaluating -----------------------------------------------------------------------

    /** A task as the rule sees it. Built once per task by [compile]'s predicate. */
    private class Values(val task: Task) {
        val labels: List<Long> by lazy { task.labels.map { it.id } }
        val assignees: List<String> by lazy { task.assignees.map { it.username } }
        val reminders: List<Instant> by lazy { task.reminders.mapNotNull { DateUtils.parseIsoDate(it.reminder) } }

        fun date(field: String): Instant? = when (field) {
            "due_date" -> DateUtils.parseIsoDate(task.dueDate)
            "start_date" -> DateUtils.parseIsoDate(task.startDate)
            "end_date" -> DateUtils.parseIsoDate(task.endDate)
            "done_at" -> DateUtils.parseIsoDate(task.doneAt)
            "created" -> DateUtils.parseIsoDate(task.created)
            "updated" -> DateUtils.parseIsoDate(task.updated)
            else -> null
        }

        fun whole(field: String): Long = when (field) {
            "priority" -> task.priority.toLong()
            "project", "project_id" -> task.projectId
            "repeat_after" -> task.repeatAfter
            "id" -> task.id
            "index" -> task.index
            "created_by_id" -> task.createdBy.id
            else -> 0L
        }

        fun text(field: String): String = when (field) {
            "title" -> task.title
            "description" -> task.description
            "hex_color" -> task.hexColor
            else -> ""
        }
    }

    private fun <T : Comparable<T>> compare(op: Op, left: T, right: T): Boolean = when (op) {
        Op.EQ, Op.IN -> left == right
        Op.NEQ, Op.NOT_IN -> left != right
        Op.GT -> left > right
        Op.GTE -> left >= right
        Op.LT -> left < right
        Op.LTE -> left <= right
        Op.LIKE -> false
    }

    /** Whether one value passes one term (for many-valued fields, one of the task's values). */
    private fun <T : Comparable<T>> passes(op: Op, value: T, wanted: List<T>): Boolean = when (op) {
        Op.IN, Op.EQ -> wanted.any { it == value }
        Op.NOT_IN, Op.NEQ -> wanted.none { it == value }
        else -> compare(op, value, wanted.first())
    }

    /**
     * The predicate of a valid rule at [now] in [zone]. [includeNulls] is the list's
     * `rule_include_nulls`. An invalid rule matches no task; an empty one every task.
     */
    fun compile(text: String?, includeNulls: Boolean, now: Instant, zone: TimeZone): (Task) -> Boolean {
        if (text.isNullOrBlank()) return { true }
        val parsed = parse(text) as? Parsed.Ok ?: return { false }
        if (check(parsed.root) != null) return { false }
        val root = compileGroup(parsed.root, includeNulls, now, zone) ?: return { true }
        return { task -> root(Values(task)) }
    }

    /** Null when nothing in the group counts (every term skipped). */
    private fun compileGroup(group: Group, nulls: Boolean, now: Instant, zone: TimeZone): ((Values) -> Boolean)? {
        val parts = mutableListOf<Pair<Join, (Values) -> Boolean>>()
        val items = group.items
        var i = 0
        while (i < items.size) {
            val item = items[i]
            when (val node = item.node) {
                is Group -> compileGroup(node, nulls, now, zone)?.let { parts += item.join to it }
                is Term -> {
                    val kind = FIELDS.getValue(node.field)
                    if (node.op == Op.LIKE && (kind == Kind.USERS || kind == Kind.CREATOR)) {
                        i++
                        continue
                    }
                    if (isMany(kind) && node.op.isRange) {
                        // Consecutive && range terms on the same field hold for one value together.
                        val run = mutableListOf(node)
                        while (i + 1 < items.size) {
                            val next = items[i + 1]
                            val nextTerm = next.node as? Term ?: break
                            if (next.join != Join.AND || nextTerm.field != node.field || !nextTerm.op.isRange) break
                            run += nextTerm
                            i++
                        }
                        parts += item.join to manyRange(kind, run, nulls, now, zone)
                    } else {
                        parts += item.join to compileTerm(node, kind, nulls, now, zone)
                    }
                }
            }
            i++
        }
        if (parts.isEmpty()) return null
        return { values ->
            var result = parts[0].second(values)
            for (k in 1 until parts.size) {
                val (join, check) = parts[k]
                result = if (join == Join.OR) result || check(values) else result && check(values)
            }
            result
        }
    }

    private fun dates(term: Term, now: Instant, zone: TimeZone): List<Instant> {
        val texts = if (term.op.isList) term.items() else listOf(term.value)
        return texts.map { parseDate(it, now, zone)!! }
    }

    private fun manyRange(kind: Kind, run: List<Term>, nulls: Boolean, now: Instant, zone: TimeZone): ((Values) -> Boolean) {
        return when (kind) {
            Kind.LABELS -> {
                val wanted = run.map { it.op to parseWhole(it.value)!! }
                ({ v -> v.labels.any { l -> wanted.all { (op, w) -> compare(op, l, w) } } || (nulls && v.labels.isEmpty()) })
            }
            Kind.USERS -> {
                val wanted = run.map { it.op to it.items().first() }
                ({ v -> v.assignees.any { a -> wanted.all { (op, w) -> compare(op, a, w) } } || (nulls && v.assignees.isEmpty()) })
            }
            else -> {
                val wanted = run.map { it.op to dates(it, now, zone).first() }
                ({ v -> v.reminders.any { r -> wanted.all { (op, w) -> compare(op, r, w) } } || (nulls && v.reminders.isEmpty()) })
            }
        }
    }

    private fun compileTerm(term: Term, kind: Kind, nulls: Boolean, now: Instant, zone: TimeZone): ((Values) -> Boolean) {
        val op = term.op
        val texts = if (op.isList) term.items() else listOf(term.value)
        return when (kind) {
            Kind.BOOL -> {
                val wanted = texts.map { if (parseBool(it)!!) 1 else 0 }
                ({ v -> passes(op, if (v.task.done) 1 else 0, wanted) })
            }
            Kind.WHOLE -> {
                val wanted = texts.map { parseWhole(it)!! }
                ({ v -> v.whole(term.field).let { passes(op, it, wanted) || (nulls && it == 0L) } })
            }
            Kind.NUMBER -> {
                val wanted = texts.map { it.toDouble() }
                ({ v -> passes(op, v.task.percentDone, wanted) })
            }
            Kind.DATE -> {
                val wanted = dates(term, now, zone)
                ({ v -> v.date(term.field).let { if (it == null) nulls else passes(op, it, wanted) } })
            }
            Kind.TEXT -> if (op == Op.LIKE) {
                val needle = term.value.lowercase()
                ({ v -> v.text(term.field).lowercase().contains(needle) })
            } else {
                ({ v -> passes(op, v.text(term.field), texts) })
            }
            Kind.LABELS -> {
                val wanted = texts.map { parseWhole(it)!! }
                many(op, nulls, wanted) { it.labels }
            }
            Kind.USERS -> {
                val wanted = term.items().filter { it.isNotEmpty() }
                many(op, nulls, wanted) { it.assignees }
            }
            Kind.REMINDERS -> {
                val wanted = dates(term, now, zone)
                many(op, nulls, wanted) { it.reminders }
            }
            Kind.CREATOR -> {
                val wanted = term.items().filter { it.isNotEmpty() }
                ({ v ->
                    val name = v.task.createdBy.username
                    if (op == Op.NEQ || op == Op.NOT_IN) name !in wanted else name in wanted
                })
            }
        }
    }

    /** A many-valued field outside a range run: `=`, `in` any; `!=`, `not in` none. */
    private fun <T : Comparable<T>> many(op: Op, nulls: Boolean, wanted: List<T>, of: (Values) -> List<T>): ((Values) -> Boolean) = { v ->
        val values = of(v)
        val hit = when (op) {
            Op.NEQ, Op.NOT_IN -> values.none { it in wanted }
            Op.EQ, Op.IN -> values.any { it in wanted }
            else -> values.any { compare(op, it, wanted.first()) }
        }
        hit || (nulls && values.isEmpty())
    }

    // --- Writing --------------------------------------------------------------------------

    /** [root] without the terms on [fields], and groups left empty; null when nothing changed. */
    fun withoutTerms(root: Group, fields: Set<String>): Pair<Group, Set<String>>? {
        val removed = mutableSetOf<String>()
        fun strip(group: Group): Group {
            val kept = mutableListOf<Item>()
            for (item in group.items) {
                when (val node = item.node) {
                    is Term -> if (node.field in fields) removed += node.field else kept += item
                    is Group -> {
                        val inner = strip(node)
                        if (inner.items.isNotEmpty()) kept += item.copy(node = inner)
                    }
                }
            }
            return Group(kept.mapIndexed { index, it -> if (index == 0) it.copy(join = Join.AND) else it })
        }
        val result = strip(root)
        return if (removed.isEmpty()) null else result to removed
    }

    private fun needsQuotes(value: String, list: Boolean): Boolean =
        value.isEmpty() || value.first() == ' ' || value.last() == ' ' ||
            value.any { it in "&|()'\"\\" } || (!list && ',' in value)

    /** A value as section 3.7 writes it. */
    fun writeValue(value: String, list: Boolean): String =
        if (needsQuotes(value, list)) "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'" else value

    /** [group] as text, the way section 3.7 writes a rule out. */
    fun write(group: Group): String = buildString {
        group.items.forEachIndexed { index, item ->
            if (index > 0) append(if (item.join == Join.OR) " || " else " && ")
            when (val node = item.node) {
                is Group -> append('(').append(write(node)).append(')')
                is Term -> {
                    append(node.field).append(' ').append(node.op.text).append(' ')
                    if (node.op.isList) {
                        append(writeValue(node.items().joinToString(", "), list = true))
                    } else {
                        append(writeValue(node.value, list = false))
                    }
                }
            }
        }
    }

    /** Whether [text] has a `like` term (the server cannot be asked for it, section 3.5). */
    fun usesLike(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val root = (parse(text) as? Parsed.Ok)?.root ?: return false
        fun any(group: Group): Boolean = group.items.any {
            when (val node = it.node) {
                is Group -> any(node)
                is Term -> node.op == Op.LIKE
            }
        }
        return any(root)
    }

    /** Every term of [text] in source order, for editors; empty when it does not read. */
    fun terms(text: String): List<Term> {
        val root = (parse(text) as? Parsed.Ok)?.root ?: return emptyList()
        val out = mutableListOf<Term>()
        fun walk(group: Group) {
            group.items.forEach {
                when (val node = it.node) {
                    is Group -> walk(node)
                    is Term -> out += node
                }
            }
        }
        walk(root)
        return out
    }
}
