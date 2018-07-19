package cn.net.polyglot.handler

import cn.net.polyglot.config.ActionConstants.DELETE
import cn.net.polyglot.config.ActionConstants.LIST
import cn.net.polyglot.config.ActionConstants.LOGIN
import cn.net.polyglot.config.ActionConstants.REGISTRY
import cn.net.polyglot.config.ActionConstants.REQUEST
import cn.net.polyglot.config.ActionConstants.RESPONSE
import cn.net.polyglot.config.FileSystemConstants.USER_DIR
import cn.net.polyglot.config.FileSystemConstants.USER_FILE
import cn.net.polyglot.config.TypeConstants.FRIEND
import cn.net.polyglot.config.TypeConstants.SEARCH
import cn.net.polyglot.config.TypeConstants.USER
import cn.net.polyglot.utils.contains
import cn.net.polyglot.utils.getUserDirAndFile
import cn.net.polyglot.utils.removeCrypto
import io.vertx.core.file.FileSystem
import io.vertx.core.json.JsonObject
import java.io.File.separator

fun handleRequests(fs: FileSystem, json: JsonObject, type: String): JsonObject {
  return when (type) {
    SEARCH -> searchUser(fs, json)
    FRIEND -> friend(fs, json)
    USER -> userAuthorize(fs, json)
    else -> defaultMessage(fs, json)
  }
}

fun userAuthorize(fs: FileSystem, json: JsonObject, loginTcpAction: () -> Unit = {}): JsonObject {
  val action = json.getString("action")

  val id = json.getString("user")
  val crypto = json.getString("crypto")
  fun handleUserCheckIdAndCrypto(id: String?, json: JsonObject, crypto: String?): Boolean {
    if (id == null || crypto == null) return false
    if (!id.checkIdValid()) {
      json.put("info", "用户名格式错误")
      return false
    }
    if (!crypto.checkCryptoValid()) {
      json.put("info", "秘钥错误")
      return false
    }
    return true
  }
  if (!handleUserCheckIdAndCrypto(id, json, crypto)) return json

  val (userDir, userFile) = getUserDirAndFile(id)
  return when (action) {
    LOGIN -> handleUserLogin(fs, json, userFile, id, crypto, loginTcpAction)
    REGISTRY -> handleUserRegistry(fs, json, userFile, id, userDir)
    else -> defaultMessage(fs, json)
  }
}


fun searchUser(fs: FileSystem, json: JsonObject): JsonObject {
  val id = json.getString("user")
  val userFile = "$USER_DIR$separator$id$separator$USER_FILE"

  try {
    val buffer = fs.readFileBlocking(userFile)
    val resJson = buffer.toJsonObject()
    resJson.removeCrypto()
    json.put("user", resJson)
  } catch (e: Exception) {
    json.putNull("user")
  } finally {
    return json
  }
}

/**
 *
 * @param fs FileSystem
 * @param json JsonObject
 * @param directlySend () -> Unit 直接发送
 * @param indirectlySend () -> Unit 非直接发送。适用于不同域名以及对方不在线时的场景
 * @return JsonObject
 */
fun message(fs: FileSystem, json: JsonObject,
            directlySend: (to: String) -> Unit = {},
            indirectlySend: (to: String) -> Unit = { }): JsonObject {
  val outputJson = json.copy()
  val from = outputJson.getString("from")
  val to = outputJson.getString("to")

  val isSameDomain =
    when {
      from == null || to == null -> false
      '@' !in from || '@' !in to -> true
      else -> from.substringAfterLast("@") == to.substringAfterLast("@")
    }

  if (isSameDomain) {
    val userDir = "$USER_DIR$separator$to"
    val receiverExist = fs.existsBlocking(userDir)
    if (receiverExist) {
      outputJson.put("info", "OK")
      directlySend(to)
    } else {
      outputJson.put("info", "no such user $to")
      indirectlySend(to)
    }
  } else {
    outputJson.put("info", "send message to other domain")
    indirectlySend(to)
  }
  return outputJson
}

fun friend(fs: FileSystem, json: JsonObject): JsonObject {
  val action = json.getString("action")
  val from = json.getString("from")
  val to = json.getString("to")
  fun checkFromValid(json: JsonObject): Boolean {
    if ("from" !in json) {
      json.put("info", "failed on account of key")
      return false
    }
    return true
  }
  if (!checkFromValid(json)) return json
  return when (action) {
    DELETE -> handleFriendDelete(fs, json, from, to)
//   request to be friends
    REQUEST -> handleFriendRequest(fs, json)
//   reply whether to accept the request
    RESPONSE -> handleFriendResponse(fs, json, from, to)
//    list friends
    LIST -> handleFriendList(fs, json, from)
    else -> defaultMessage(fs, json)
  }
}

fun defaultMessage(fs: FileSystem, json: JsonObject): JsonObject {
  json.removeAll { it.key !in arrayOf("version", "type") }
  json.put("info", "Default info, please check all sent value is correct.")
  return json
}
