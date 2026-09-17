package io.clarionchain.keel.core

import org.json.JSONObject

data class BackupManifest(
    val schemaVersion: Int,
    val network: BitcoinNetwork,
    val walletFingerprint: String,
    val generation: Long,
    val createdAtEpochMs: Long,
    val barkBindingVersion: String,
    val appVersion: String,
    val contentSha256: String,
) {
    init {
        require(schemaVersion >= 1)
        require(generation >= 1L)
        require(walletFingerprint.isNotBlank())
        require(contentSha256.length == 64)
        require(walletFingerprint.none { it.isWhitespace() })
    }

    fun toJsonString(): String = JSONObject()
        .put("schemaVersion", schemaVersion)
        .put("network", network.name)
        .put("walletFingerprint", walletFingerprint)
        .put("generation", generation)
        .put("createdAtEpochMs", createdAtEpochMs)
        .put("barkBindingVersion", barkBindingVersion)
        .put("appVersion", appVersion)
        .put("contentSha256", contentSha256)
        .toString()
}

fun parseBackupManifest(json: String): BackupManifest {
    val o = JSONObject(json)
    return BackupManifest(
        schemaVersion = o.getInt("schemaVersion"),
        network = BitcoinNetwork.valueOf(o.getString("network")),
        walletFingerprint = o.getString("walletFingerprint"),
        generation = o.getLong("generation"),
        createdAtEpochMs = o.getLong("createdAtEpochMs"),
        barkBindingVersion = o.getString("barkBindingVersion"),
        appVersion = o.getString("appVersion"),
        contentSha256 = o.getString("contentSha256"),
    )
}

object BackupRollback {
    fun canReplace(liveGeneration: Long?, incoming: BackupManifest): Boolean {
        if (liveGeneration == null) return true
        return incoming.generation >= liveGeneration
    }
}
