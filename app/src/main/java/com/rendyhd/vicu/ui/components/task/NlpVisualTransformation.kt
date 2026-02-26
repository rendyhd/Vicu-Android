package com.rendyhd.vicu.ui.components.task

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import com.rendyhd.vicu.util.parser.ParsedToken

class NlpVisualTransformation(
    private val tokens: List<ParsedToken>,
    private val isDarkTheme: Boolean,
) : VisualTransformation {

    override fun filter(text: AnnotatedString): TransformedText {
        if (tokens.isEmpty()) {
            return TransformedText(text, OffsetMapping.Identity)
        }

        val builder = AnnotatedString.Builder(text)
        for (token in tokens) {
            val start = token.start.coerceIn(0, text.length)
            val end = token.end.coerceIn(0, text.length)
            if (start >= end) continue
            builder.addStyle(
                SpanStyle(
                    color = tokenTextColor(token.type, isDarkTheme),
                    background = tokenBgColor(token.type, isDarkTheme),
                ),
                start,
                end,
            )
        }
        return TransformedText(builder.toAnnotatedString(), OffsetMapping.Identity)
    }
}
