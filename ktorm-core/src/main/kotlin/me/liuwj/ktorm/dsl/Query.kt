/*
 * Copyright 2018-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.liuwj.ktorm.dsl

import me.liuwj.ktorm.database.Database
import me.liuwj.ktorm.database.iterator
import me.liuwj.ktorm.expression.*
import me.liuwj.ktorm.schema.BooleanSqlType
import me.liuwj.ktorm.schema.Column
import me.liuwj.ktorm.schema.ColumnDeclaring
import java.sql.ResultSet

/**
 * [Query] is an abstraction of query operations and the core class of Ktorm's query DSL.
 *
 * The constructor of this class accepts two parameters: [database] is the database instance that this query
 * is running on; [expression] is the abstract representation of the executing SQL statement. Usually, we don't
 * use the constructor to create [Query] objects but use the `database.from(..).select(..)` syntax instead.
 *
 * [Query] implements the [Iterable] interface, so we can iterate the results by a for-each loop:
 *
 * ```kotlin
 * for (row in database.from(Employees).select()) {
 *     println(row[Employees.name])
 * }
 * ```
 *
 * Moreover, there are many extension functions for [Iterable] in Kotlin standard lib, so we can also
 * process the results via functions such as [Iterable.map], [Iterable.filter], [Iterable.reduce], etc.
 *
 * Query objects are immutable. Query DSL functions are provided as its extension functions normally. We can
 * chaining call these functions to modify them and create new query objects. Here is a simple example:
 *
 * ```kotlin
 * val query = database
 *     .from(Employees)
 *     .select(Employees.salary)
 *     .where { (Employees.departmentId eq 1) and (Employees.name like "%vince%") }
 * ```
 *
 * Easy to know that the query obtains the salary of an employee named vince in department 1. The generated
 * SQL is obviously:
 *
 * ```sql
 * select t_employee.salary as t_employee_salary
 * from t_employee
 * where (t_employee.department_id = ?) and (t_employee.name like ?)
 * ```
 *
 * More usages can be found in the documentations of those DSL functions.
 *
 * @property database the [Database] instance that this query is running on.
 * @property expression the underlying SQL expression of this query object.
 */
data class Query(val database: Database, val expression: QueryExpression) {

    /**
     * The executable SQL string of this query.
     *
     * Useful when we want to ensure if the generated SQL is expected while debugging.
     */
    val sql: String by lazy(LazyThreadSafetyMode.NONE) {
        database.formatExpression(expression, beautifySql = true).first
    }

    /**
     * The [ResultSet] object of this query, lazy initialized after first access, obtained from the database by
     * executing the generated SQL.
     *
     * Note that the return type of this property is not a normal [ResultSet], but a [QueryRowSet] instead. That's
     * a special implementation provided by Ktorm, different from normal result sets, it is available offline and
     * overrides the indexed access operator. More details can be found in the documentation of [QueryRowSet].
     */
    val rowSet: QueryRowSet by lazy(LazyThreadSafetyMode.NONE) {
        QueryRowSet(this, database.executeQuery(expression))
    }

    /**
     * The total record count of this query ignoring the pagination params.
     *
     * If the query doesn't limits the results via [Query.limit] function, return the size of the result set. Or if
     * it does, return the total record count of the query ignoring the offset and limit parameters. This property
     * is provided to support pagination, we can calculate the page count through dividing it by out page size.
     */
    val totalRecords: Int by lazy(LazyThreadSafetyMode.NONE) {
        if (expression.offset == null && expression.limit == null) {
            rowSet.size()
        } else {
            val countExpr = expression.toCountExpression()
            val rowSet = database.executeQuery(countExpr)

            if (rowSet.next()) {
                rowSet.getInt(1).also { total ->
                    if (database.logger.isDebugEnabled()) {
                        database.logger.debug("Total Records: $total")
                    }
                }
            } else {
                val (sql, _) = database.formatExpression(countExpr, beautifySql = true)
                throw IllegalStateException("No result return for sql: $sql")
            }
        }
    }

    /**
     * Return an iterator over the rows of this query.
     *
     * Note that this function is simply implemented as `rowSet.iterator()`, so every element returned by the iterator
     * exactly shares the same instance as the [rowSet] property.
     *
     * @see rowSet
     * @see ResultSet.iterator
     */
    operator fun iterator(): Iterator<QueryRowSet> {
        return rowSet.iterator()
    }
}

/**
 * Create a query object, selecting the specific columns or expressions from this [QuerySource].
 *
 * Note that the specific columns can be empty, that means `select *` in SQL.
 *
 * @since 2.7
 */
fun QuerySource.select(columns: Collection<ColumnDeclaring<*>>): Query {
    val declarations = columns.map { it.asDeclaringExpression() }
    return Query(database, SelectExpression(columns = declarations, from = expression))
}

/**
 * Create a query object, selecting the specific columns or expressions from this [QuerySource].
 *
 * Note that the specific columns can be empty, that means `select *` in SQL.
 *
 * @since 2.7
 */
fun QuerySource.select(vararg columns: ColumnDeclaring<*>): Query {
    return select(columns.asList())
}

/**
 * Create a query object, selecting the specific columns or expressions from this [QuerySource] distinctly.
 *
 * Note that the specific columns can be empty, that means `select distinct *` in SQL.
 *
 * @since 2.7
 */
fun QuerySource.selectDistinct(columns: Collection<ColumnDeclaring<*>>): Query {
    val declarations = columns.map { it.asDeclaringExpression() }
    return Query(database, SelectExpression(columns = declarations, from = expression, isDistinct = true))
}

/**
 * Create a query object, selecting the specific columns or expressions from this [QuerySource] distinctly.
 *
 * Note that the specific columns can be empty, that means `select distinct *` in SQL.
 *
 * @since 2.7
 */
fun QuerySource.selectDistinct(vararg columns: ColumnDeclaring<*>): Query {
    return selectDistinct(columns.asList())
}

private fun <T : Any> ColumnDeclaring<T>.asDeclaringExpression(): ColumnDeclaringExpression<T> {
    return when (this) {
        is ColumnDeclaringExpression -> this
        is Column -> this.aliased(label)
        else -> this.aliased(null)
    }
}

/**
 * Specify the `where` clause of this query using the expression returned by the given callback function.
 */
inline fun Query.where(block: () -> ColumnDeclaring<Boolean>): Query {
    return this.copy(
        expression = when (expression) {
            is SelectExpression -> expression.copy(where = block().asExpression())
            is UnionExpression -> throw IllegalStateException("Where clause is not supported in a union expression.")
        }
    )
}

/**
 * Create a mutable list, then add filter conditions to the list in the given callback function, finally combine
 * them with the [and] operator and set the combined condition as the `where` clause of this query.
 *
 * Note that if we don't add any conditions to the list, the `where` clause would not be set.
 */
inline fun Query.whereWithConditions(block: (MutableList<ColumnDeclaring<Boolean>>) -> Unit): Query {
    val conditions = ArrayList<ColumnDeclaring<Boolean>>().apply(block)

    if (conditions.isEmpty()) {
        return this
    } else {
        return this.where { conditions.reduce { a, b -> a and b } }
    }
}

/**
 * Create a mutable list, then add filter conditions to the list in the given callback function, finally combine
 * them with the [or] operator and set the combined condition as the `where` clause of this query.
 *
 * Note that if we don't add any conditions to the list, the `where` clause would not be set.
 */
inline fun Query.whereWithOrConditions(block: (MutableList<ColumnDeclaring<Boolean>>) -> Unit): Query {
    val conditions = ArrayList<ColumnDeclaring<Boolean>>().apply(block)

    if (conditions.isEmpty()) {
        return this
    } else {
        return this.where { conditions.reduce { a, b -> a or b } }
    }
}

/**
 * Combine this iterable of boolean expressions with the [and] operator.
 *
 * If the iterable is empty, `true` will be returned.
 */
fun Iterable<ColumnDeclaring<Boolean>>.combineConditions(): ColumnDeclaring<Boolean> {
    if (this.any()) {
        return this.reduce { a, b -> a and b }
    } else {
        return ArgumentExpression(true, BooleanSqlType)
    }
}

/**
 * Specify the `group by` clause of this query using the given columns or expressions.
 */
fun Query.groupBy(vararg columns: ColumnDeclaring<*>): Query {
    return this.copy(
        expression = when (expression) {
            is SelectExpression -> expression.copy(groupBy = columns.map { it.asExpression() })
            is UnionExpression -> throw IllegalStateException("Group by clause is not supported in a union expression.")
        }
    )
}

/**
 * Specify the `having` clause of this query using the expression returned by the given callback function.
 */
inline fun Query.having(block: () -> ColumnDeclaring<Boolean>): Query {
    return this.copy(
        expression = when (expression) {
            is SelectExpression -> expression.copy(having = block().asExpression())
            is UnionExpression -> throw IllegalStateException("Having clause is not supported in a union expression.")
        }
    )
}

/**
 * Specify the `order by` clause of this query using the given order-by expressions.
 */
fun Query.orderBy(vararg orders: OrderByExpression): Query {
    return this.copy(
        expression = when (expression) {
            is SelectExpression -> expression.copy(orderBy = orders.asList())
            is UnionExpression -> {
                val replacer = OrderByReplacer(expression)
                expression.copy(orderBy = orders.map { replacer.visit(it) as OrderByExpression })
            }
        }
    )
}

private class OrderByReplacer(query: UnionExpression) : SqlExpressionVisitor() {
    val declaringColumns = query.findDeclaringColumns()

    override fun visitOrderBy(expr: OrderByExpression): OrderByExpression {
        val declaring = declaringColumns.find { it.declaredName != null && it.expression == expr.expression }

        if (declaring == null) {
            throw IllegalArgumentException("Could not find the ordering column in the union expression, column: $expr")
        } else {
            return OrderByExpression(
                expression = ColumnExpression(
                    tableAlias = null,
                    name = declaring.declaredName!!,
                    sqlType = declaring.expression.sqlType
                ),
                orderType = expr.orderType
            )
        }
    }
}

internal tailrec fun QueryExpression.findDeclaringColumns(): List<ColumnDeclaringExpression<*>> {
    return when (this) {
        is SelectExpression -> columns
        is UnionExpression -> left.findDeclaringColumns()
    }
}

/**
 * Order this column or expression in ascending order.
 */
fun ColumnDeclaring<*>.asc(): OrderByExpression {
    return OrderByExpression(asExpression(), OrderType.ASCENDING)
}

/**
 * Order this column or expression in descending order, corresponding to the `desc` keyword in SQL.
 */
fun ColumnDeclaring<*>.desc(): OrderByExpression {
    return OrderByExpression(asExpression(), OrderType.DESCENDING)
}

/**
 * Specify the pagination parameters of this query.
 *
 * This function requires a dialect enabled, different SQLs will be generated with different dialects. For example,
 * `limit ?, ?` by MySQL, `limit m offset n` by PostgreSQL.
 *
 * Note that if both [offset] and [limit] are zero, they will be ignored.
 */
fun Query.limit(offset: Int, limit: Int): Query {
    if (offset == 0 && limit == 0) {
        return this
    }

    return this.copy(
        expression = when (expression) {
            is SelectExpression -> expression.copy(offset = offset, limit = limit)
            is UnionExpression -> expression.copy(offset = offset, limit = limit)
        }
    )
}

/**
 * Union this query with the given one, corresponding to the `union` keyword in SQL.
 */
fun Query.union(right: Query): Query {
    return this.copy(expression = UnionExpression(left = expression, right = right.expression, isUnionAll = false))
}

/**
 * Union this query with the given one, corresponding to the `union all` keyword in SQL.
 */
fun Query.unionAll(right: Query): Query {
    return this.copy(expression = UnionExpression(left = expression, right = right.expression, isUnionAll = true))
}

fun Query.asIterable(): Iterable<QueryRowSet> {
    return Iterable { iterator() }
}

inline fun Query.forEach(action: (row: QueryRowSet) -> Unit) {
    for (row in this) action(row)
}

inline fun Query.forEachIndexed(action: (index: Int, row: QueryRowSet) -> Unit) {
    var index = 0
    for (row in this) action(index++, row)
}

fun Query.withIndex(): Iterable<IndexedValue<QueryRowSet>> {
    return asIterable().withIndex()
}

inline fun <R> Query.map(transform: (row: QueryRowSet) -> R): List<R> {
    return mapTo(ArrayList(), transform)
}

inline fun <R, C : MutableCollection<in R>> Query.mapTo(destination: C, transform: (row: QueryRowSet) -> R): C {
    for (row in this) destination += transform(row)
    return destination
}

inline fun <R : Any> Query.mapNotNull(transform: (row: QueryRowSet) -> R?): List<R> {
    return mapNotNullTo(ArrayList(), transform)
}

inline fun <R : Any, C : MutableCollection<in R>> Query.mapNotNullTo(
    destination: C,
    transform: (row: QueryRowSet) -> R?
): C {
    forEach { row -> transform(row)?.let { destination += it } }
    return destination
}

inline fun <R> Query.mapIndexed(transform: (index: Int, row: QueryRowSet) -> R): List<R> {
    return mapIndexedTo(ArrayList(), transform)
}

inline fun <R, C : MutableCollection<in R>> Query.mapIndexedTo(
    destination: C,
    transform: (index: Int, row: QueryRowSet) -> R
): C {
    var index = 0
    return mapTo(destination) { row -> transform(index++, row) }
}

inline fun <R : Any> Query.mapIndexedNotNull(transform: (index: Int, row: QueryRowSet) -> R?): List<R> {
    return mapIndexedNotNullTo(ArrayList(), transform)
}

inline fun <R : Any, C : MutableCollection<in R>> Query.mapIndexedNotNullTo(
    destination: C,
    transform: (index: Int, row: QueryRowSet) -> R?
): C {
    forEachIndexed { index, row -> transform(index, row)?.let { destination += it } }
    return destination
}

inline fun <K, V> Query.associate(transform: (row: QueryRowSet) -> Pair<K, V>): Map<K, V> {
    return asIterable().associate(transform)
}

inline fun <K, V> Query.associateBy(
    keySelector: (row: QueryRowSet) -> K,
    valueTransform: (row: QueryRowSet) -> V
): Map<K, V> {
    return asIterable().associateBy(keySelector, valueTransform)
}

inline fun <K, V, M : MutableMap<in K, in V>> Query.associateTo(
    destination: M,
    transform: (row: QueryRowSet) -> Pair<K, V>
): M {
    return asIterable().associateTo(destination, transform)
}

inline fun <K, V, M : MutableMap<in K, in V>> Query.associateByTo(
    destination: M,
    keySelector: (row: QueryRowSet) -> K,
    valueTransform: (row: QueryRowSet) -> V
): M {
    return asIterable().associateByTo(destination, keySelector, valueTransform)
}
