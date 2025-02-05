package com.navercorp.scavenger.entity

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

@Table("jvms")
data class Jvm(
    @Id
    val id: Long = 0,

    @Column("customerId")
    val customerId: Long,

    @Column("applicationId")
    val applicationId: Long,

    @Column("environmentId")
    val environmentId: Long,

    @Column("uuid")
    val uuid: String,

    @Column("codeBaseFingerprint")
    val codeBaseFingerprint: String?,

    @Column("createdAt")
    val createdAt: Instant,

    @Column("publishedAt")
    val publishedAt: Instant,

    @Column("hostname")
    val hostname: String,
)
