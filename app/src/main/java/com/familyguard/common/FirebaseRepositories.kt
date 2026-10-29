package com.familyguard.common

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseReference
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.suspendCancellableCoroutine
import java.security.SecureRandom
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class AuthRepository(private val auth: FirebaseAuth = FirebaseAuth.getInstance()) {
    val currentUser get() = auth.currentUser
    suspend fun signIn(email: String, password: String) { auth.signInWithEmailAndPassword(email.trim(), password).await() }
    suspend fun createAccount(email: String, password: String) { auth.createUserWithEmailAndPassword(email.trim(), password).await() }

    suspend fun signInGuest(username: String, password: String): String {
        val normalized = validateGuestCredentials(username, password)
        val user = auth.signInWithEmailAndPassword(guestEmail(normalized), password).await().user ?: error("Could not sign in.")
        saveGuestProfile(user.uid, normalized, createParentRole = false)
        return normalized
    }

    suspend fun createGuest(username: String, password: String): String {
        val normalized = validateGuestCredentials(username, password)
        val user = auth.createUserWithEmailAndPassword(guestEmail(normalized), password).await().user ?: error("Could not create the account.")
        try {
            saveGuestProfile(user.uid, normalized, createParentRole = true)
        } catch (error: Exception) {
            runCatching { user.delete().await() }
            auth.signOut()
            throw error
        }
        return normalized
    }

    private fun validateGuestCredentials(username: String, password: String): String {
        val normalized = normalizeUsername(username)
        require(normalized.matches(Regex("[a-z0-9_]{3,24}"))) { "Use 3 to 24 letters, numbers, or underscores." }
        require(password.length >= 6) { "Password must be at least 6 characters." }
        return normalized
    }

    private suspend fun saveGuestProfile(uid: String, normalized: String, createParentRole: Boolean) {
        val key = usernameKey(normalized)
        val indexRef = FirebaseDatabase.getInstance().getReference("usernameIndex").child(key)
        val reserved = suspendCancellableCoroutine<Boolean> { continuation ->
            indexRef.runTransaction(object : com.google.firebase.database.Transaction.Handler {
                override fun doTransaction(currentData: com.google.firebase.database.MutableData): com.google.firebase.database.Transaction.Result {
                    val existing = currentData.value as? String
                    if (existing != null && existing != uid) return com.google.firebase.database.Transaction.abort()
                    currentData.value = uid
                    return com.google.firebase.database.Transaction.success(currentData)
                }
                override fun onComplete(error: com.google.firebase.database.DatabaseError?, committed: Boolean, currentData: DataSnapshot?) {
                    if (!continuation.isActive) return
                    if (error != null) continuation.resumeWithException(error.toException()) else continuation.resume(committed)
                }
            })
        }
        check(reserved) { "That username is already taken." }
        val profile = mutableMapOf<String, Any?>("users/$uid/username" to normalized)
        if (createParentRole) profile["users/$uid/role"] = UserRole.PARENT.name
        FirebaseDatabase.getInstance().reference.updateChildren(profile).await()
    }
    fun signOut() = auth.signOut()
    companion object {
        fun normalizeUsername(username: String) = username.trim().lowercase(Locale.ROOT)
        fun usernameKey(normalized: String): String = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        fun guestEmail(normalized: String) = "$normalized@guest.familyman.invalid"
    }
}

class FamilyRepository(private val db: FirebaseDatabase = FirebaseDatabase.getInstance()) {
    private val users get() = db.getReference("users")
    private val families get() = db.getReference("families")
    private val pairingCodes get() = db.getReference("pairingCodes")
    private val referrals get() = db.getReference("referrals")

    suspend fun getRole(uid: String): UserRole? = users.child(uid).child("role").get().await().getValue(String::class.java)?.let { runCatching { UserRole.valueOf(it) }.getOrNull() }
    suspend fun getChildFamilyId(uid: String): String? = users.child(uid).child("familyId").get().await().getValue(String::class.java)
    suspend fun getUsername(uid: String): String = users.child(uid).child("username").get().await().getValue(String::class.java).orEmpty()
    suspend fun chooseRole(uid: String, role: UserRole) { users.child(uid).child("role").setValue(role.name).await() }

    suspend fun createPairingCode(parentUid: String): String {
        val existing = users.child(parentUid).child("familyId").get().await().getValue(String::class.java)
        val familyId = existing ?: (families.push().key ?: error("Could not create a family ID"))
        val code = newCode()
        require(!pairingCodes.child(code).get().await().exists()) { "Could not create a unique pairing code. Please try again." }
        val expiresAt = System.currentTimeMillis() + CODE_LIFETIME_MS
        val updates = mutableMapOf<String, Any?>("families/$familyId/parentUid" to parentUid, "families/$familyId/createdAt" to System.currentTimeMillis(), "users/$parentUid/familyId" to familyId, "pairingCodes/$code/familyId" to familyId, "pairingCodes/$code/parentUid" to parentUid, "pairingCodes/$code/expiresAt" to expiresAt)
        db.reference.updateChildren(updates).await()
        return code
    }

    suspend fun createFriendReferralCode(parentUid: String): String {
        val familyId = users.child(parentUid).child("familyId").get().await().getValue(String::class.java) ?: error("Create your family code first.")
        val code = newReferralCode()
        db.reference.updateChildren(mapOf("referrals/$code/familyId" to familyId, "referrals/$code/ownerUid" to parentUid, "referrals/$code/type" to ReferralType.FRIEND.databaseValue)).await()
        return code
    }

    suspend fun resolveParentReferralCode(rawCode: String): String {
        val code = rawCode.trim().uppercase()
        require(code.length == CODE_LENGTH && code.all(Char::isDigit)) { "Enter a valid 6-digit parent code." }
        val snapshot = pairingCodes.child(code).get().await()
        val pairing = snapshot.toPairingInfo() ?: error("Parent code not found or already used.")
        require(pairing.expiresAt > System.currentTimeMillis()) { "This code expired. Ask the parent for a new one." }
        return code
    }

    suspend fun getChildren(parentUid: String): List<ChildRecord> {
        val familyId = users.child(parentUid).child("familyId").get().await().getValue(String::class.java) ?: return emptyList()
        return families.child(familyId).child("children").get().await().children.mapNotNull { child ->
            val uid = child.key ?: return@mapNotNull null
            val kind = child.child("referralType").getValue(String::class.java)?.let(::parseReferralType) ?: ReferralType.PARENT
            ChildRecord(uid, kind, child.child("linkedAt").getValue(Long::class.java) ?: 0L)
        }
    }

    suspend fun pairChild(childUid: String, rawCode: String) {
        val code = rawCode.trim().uppercase()
        require(code.length == CODE_LENGTH && code.all(Char::isDigit)) { "Enter a valid 6-digit code." }
        val ref = pairingCodes.child(code)
        val info = ref.get().await().toPairingInfo() ?: error("Code not found or already used.")
        require(info.expiresAt > System.currentTimeMillis()) { "This code expired. Ask the parent for a new one." }
        check(claimChild(ref, childUid)) { "This code has already been used." }
        db.reference.updateChildren(mapOf("families/${info.familyId}/children/$childUid/linkedAt" to System.currentTimeMillis(), "families/${info.familyId}/children/$childUid/parentUid" to info.parentUid, "families/${info.familyId}/children/$childUid/referralType" to ReferralType.PARENT.databaseValue, "users/$childUid/familyId" to info.familyId)).await()
    }

    suspend fun redeemFriendCode(childUid: String, rawCode: String) {
        val code = rawCode.trim().uppercase()
        require(code.length in 6..32) { "Enter a valid friend referral code." }
        val info = referrals.child(code).get().await().toReferralInfo() ?: error("Friend referral code not found.")
        require(info.type == ReferralType.FRIEND) { "Enter a parent six-digit code in the connect option." }
        db.reference.updateChildren(mapOf("families/${info.familyId}/children/$childUid/linkedAt" to System.currentTimeMillis(), "families/${info.familyId}/children/$childUid/parentUid" to info.ownerUid, "families/${info.familyId}/children/$childUid/referralType" to ReferralType.FRIEND.databaseValue, "families/${info.familyId}/children/$childUid/referralCode" to code, "users/$childUid/familyId" to info.familyId)).await()
    }

    private suspend fun claimChild(ref: DatabaseReference, childUid: String): Boolean = suspendCancellableCoroutine { continuation ->
        ref.child("childUid").runTransaction(object : com.google.firebase.database.Transaction.Handler {
            override fun doTransaction(currentData: com.google.firebase.database.MutableData): com.google.firebase.database.Transaction.Result {
                val existing = currentData.value as? String
                return if (existing == null || existing == childUid) { currentData.value = childUid; com.google.firebase.database.Transaction.success(currentData) } else com.google.firebase.database.Transaction.abort()
            }
            override fun onComplete(error: com.google.firebase.database.DatabaseError?, committed: Boolean, currentData: DataSnapshot?) {
                if (!continuation.isActive) return
                if (error != null) continuation.resumeWithException(error.toException()) else continuation.resume(committed)
            }
        })
    }

    private fun DataSnapshot.toPairingInfo(): PairingInfo? {
        val family = child("familyId").getValue(String::class.java) ?: return null
        val parent = child("parentUid").getValue(String::class.java) ?: return null
        val expires = child("expiresAt").getValue(Long::class.java) ?: return null
        return PairingInfo(family, parent, expires)
    }
    private fun DataSnapshot.toReferralInfo(): ReferralInfo? {
        val family = child("familyId").getValue(String::class.java) ?: return null
        val owner = child("ownerUid").getValue(String::class.java) ?: return null
        val type = child("type").getValue(String::class.java)?.let(::parseReferralType) ?: return null
        return ReferralInfo(family, owner, type, child("expiresAt").getValue(Long::class.java))
    }
    private fun parseReferralType(value: String) = if (value.equals("friend", true)) ReferralType.FRIEND else ReferralType.PARENT
    private fun newReferralCode() = buildString(8) { repeat(8) { append(CODE_ALPHABET[SecureRandom().nextInt(CODE_ALPHABET.length)]) } }
    private fun newCode() = buildString(CODE_LENGTH) { repeat(CODE_LENGTH) { append(CODE_ALPHABET[SecureRandom().nextInt(CODE_ALPHABET.length)]) } }
    companion object { const val CODE_LENGTH = 6; const val CODE_ALPHABET = "0123456789"; const val CODE_LIFETIME_MS = 15 * 60 * 1000L }
}
