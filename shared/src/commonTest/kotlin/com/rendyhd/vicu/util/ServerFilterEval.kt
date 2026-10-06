package com.rendyhd.vicu.util

import kotlinx.datetime.Instant

/**
 * A tiny interpreter for the part of Vikunja's filter language the custom lists generate, so a
 * test can check that a server filter string is a superset of what the client-side evaluator
 * accepts. The same idea as the desktop test helper (`server-filter-eval.ts`).
 *
 * Grammar: `expr := and ('||' and)*`, `and := primary ('&&' primary)*`,
 * `primary := '(' expr ')' | field op value`. Fields: `done`, `project_id`, `due_date`. Anything
 * else throws, so a new clause cannot slip through a test unnoticed.
 */
object ServerFilterEval {

    private val nullInstant: Instant = Instant.parse(Constants.NULL_DATE_STRING)

    /** The fields of a Vikunja task the filter can look at. */
    class Row(val done: Boolean, val projectId: Long, val dueDate: String) {
        /** A due date as the server compares it: blank and the null date are the same "no date". */
        val due: Instant = if (dueDate.isBlank()) nullInstant else Instant.parse(dueDate)
    }

    private enum class Kind { PUNCT, OP, WORD, STRING }

    private data class Token(val kind: Kind, val text: String)

    private val operators = listOf(">=", "<=", "!=", "=", "<", ">")

    private fun tokenize(input: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var i = 0
        while (i < input.length) {
            val ch = input[i]
            when {
                ch.isWhitespace() -> i++
                ch == '(' || ch == ')' -> {
                    tokens += Token(Kind.PUNCT, ch.toString())
                    i++
                }
                input.startsWith("&&", i) || input.startsWith("||", i) -> {
                    tokens += Token(Kind.PUNCT, input.substring(i, i + 2))
                    i += 2
                }
                operators.any { input.startsWith(it, i) } -> {
                    val op = operators.first { input.startsWith(it, i) }
                    tokens += Token(Kind.OP, op)
                    i += op.length
                }
                ch == '\'' -> {
                    val end = input.indexOf('\'', i + 1)
                    require(end >= 0) { "Unterminated string in filter: $input" }
                    tokens += Token(Kind.STRING, input.substring(i + 1, end))
                    i = end + 1
                }
                else -> {
                    val word = Regex("^[A-Za-z0-9_.:+-]+").find(input.substring(i))
                        ?: error("Unexpected character '$ch' in filter: $input")
                    tokens += Token(Kind.WORD, word.value)
                    i += word.value.length
                }
            }
        }
        return tokens
    }

    private fun <T : Comparable<T>> compare(op: String, left: T, right: T): Boolean = when (op) {
        "=" -> left == right
        "!=" -> left != right
        "<" -> left < right
        "<=" -> left <= right
        ">" -> left > right
        ">=" -> left >= right
        else -> error("Unsupported operator $op")
    }

    /** One `field op value` comparison, with the value parsed once. */
    private fun comparison(field: String, op: String, value: String): (Row) -> Boolean = when (field) {
        "done" -> {
            require(op == "=" || op == "!=") { "Unsupported operator $op for done" }
            val wanted = if (value == "true") 1 else 0
            ({ row: Row -> compare(op, if (row.done) 1 else 0, wanted) })
        }
        "project_id" -> {
            val wanted = value.toLong()
            ({ row: Row -> compare(op, row.projectId, wanted) })
        }
        "due_date" -> {
            val wanted = if (value.isBlank()) nullInstant else Instant.parse(value)
            ({ row: Row -> compare(op, row.due, wanted) })
        }
        else -> error("Unsupported filter field $field")
    }

    /** Parses [filter] once into a predicate. A blank filter passes everything. */
    fun compile(filter: String?): (Row) -> Boolean {
        if (filter.isNullOrBlank()) return { true }
        val tokens = tokenize(filter)
        var pos = 0

        lateinit var parseOr: () -> (Row) -> Boolean

        fun parsePrimary(): (Row) -> Boolean {
            val token = tokens.getOrNull(pos) ?: error("Unexpected end of filter: $filter")
            if (token.kind == Kind.PUNCT && token.text == "(") {
                pos++
                val inner = parseOr()
                require(tokens.getOrNull(pos)?.text == ")") { "Missing ) in filter: $filter" }
                pos++
                return inner
            }
            val op = tokens.getOrNull(pos + 1)
            val value = tokens.getOrNull(pos + 2)
            require(
                token.kind == Kind.WORD && op?.kind == Kind.OP && value != null &&
                    (value.kind == Kind.WORD || value.kind == Kind.STRING),
            ) { "Cannot parse a comparison at token $pos of: $filter" }
            pos += 3
            return comparison(token.text, op!!.text, value!!.text)
        }

        fun parseAnd(): (Row) -> Boolean {
            val nodes = mutableListOf(parsePrimary())
            while (tokens.getOrNull(pos)?.text == "&&") {
                pos++
                nodes += parsePrimary()
            }
            return { row -> nodes.all { it(row) } }
        }

        parseOr = {
            val nodes = mutableListOf(parseAnd())
            while (tokens.getOrNull(pos)?.text == "||") {
                pos++
                nodes += parseAnd()
            }
            ({ row: Row -> nodes.any { it(row) } })
        }

        val root = parseOr()
        require(pos == tokens.size) { "Trailing tokens in filter: $filter" }
        return root
    }

    /** Whether [row] passes [filter]. */
    fun matches(filter: String?, row: Row): Boolean = compile(filter)(row)
}
