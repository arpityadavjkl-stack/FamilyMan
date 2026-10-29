package com.familyguard.common

enum class UserRole { PARENT, CHILD }

enum class ReferralType(val databaseValue: String) { PARENT("parent"), FRIEND("friend") }

data class PairingInfo(val familyId: String, val parentUid: String, val expiresAt: Long)
data class ReferralInfo(val familyId: String, val ownerUid: String, val type: ReferralType, val expiresAt: Long?)
data class ChildRecord(val uid: String, val referralType: ReferralType, val linkedAt: Long)
