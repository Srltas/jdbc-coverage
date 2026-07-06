package com.jdbccoverage.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class JdbcScopeTest {

    @Test
    fun `partitions 34 interfaces into 28 MAIN, 4 PERIPHERAL, 2 XA with no overlap`() {
        assertThat(JdbcScope.MAIN_INTERFACES).hasSize(28)
        assertThat(JdbcScope.PERIPHERAL_INTERFACES).hasSize(4)
        assertThat(JdbcScope.XA_INTERFACES).hasSize(2)
        assertThat(JdbcScope.ALL).hasSize(34)
        assertThat(JdbcScope.ALL.toSet()).hasSize(34) // no interface in two groups
    }

    @Test
    fun `classifies representative interfaces`() {
        assertThat(JdbcScope.groupOf("java.sql.Connection")).isEqualTo(SpecGroup.MAIN)
        assertThat(JdbcScope.groupOf("javax.sql.DataSource")).isEqualTo(SpecGroup.MAIN)
        assertThat(JdbcScope.groupOf("java.sql.SQLInput")).isEqualTo(SpecGroup.PERIPHERAL)
        assertThat(JdbcScope.groupOf("java.sql.DriverAction")).isEqualTo(SpecGroup.PERIPHERAL)
        assertThat(JdbcScope.groupOf("javax.transaction.xa.XAResource")).isEqualTo(SpecGroup.XA)
        assertThat(JdbcScope.groupOf("javax.transaction.xa.Xid")).isEqualTo(SpecGroup.XA)
    }

    @Test
    fun `unlisted interface defaults to MAIN`() {
        assertThat(JdbcScope.groupOf("com.example.Unknown")).isEqualTo(SpecGroup.MAIN)
    }
}
