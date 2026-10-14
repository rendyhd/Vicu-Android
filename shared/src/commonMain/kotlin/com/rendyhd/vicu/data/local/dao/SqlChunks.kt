package com.rendyhd.vicu.data.local.dao

/**
 * Most ids a single `IN (...)` statement may bind. SQLite before 3.32 caps bound variables at 999,
 * and Android 8-11 (minSdk is 26) ship such versions, so a query over every cached task id throws
 * "too many SQL variables" once an account has about 1,000 tasks. Room expands a list parameter to
 * one variable per element, so every DAO method that takes an unbounded id list must split it with
 * [sqlIdChunks] and run one statement per chunk.
 */
const val MAX_SQL_ID_PARAMS = 500

/** Splits [this] into lists of at most [MAX_SQL_ID_PARAMS], preserving order. Empty input gives no chunks. */
fun <T> Collection<T>.sqlIdChunks(): List<List<T>> = chunked(MAX_SQL_ID_PARAMS)
