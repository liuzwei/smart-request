package dev.smartrequest.template

/**
 * User-facing documentation for the built-in function catalog. [FunctionsDialog] renders this list
 * with examples and scope notes; [BuiltInFunctionCatalogTest] keeps it in sync with the actual
 * registry.
 */
data class BuiltInFunctionDoc(
    val category: String,
    val signature: String,
    val example: String,
    val scope: String,
    val description: String
) {
    val name: String
        get() = signature.substringBefore("(")
}

object BuiltInFunctionCatalog {
    val all: List<BuiltInFunctionDoc> = listOf(
        // Encoding.
        BuiltInFunctionDoc(
            "编码",
            "base64(value)",
            "{{base64(token)}}",
            "所有模板位置",
            "UTF-8 文本转标准 Base64。编码不是加密；用于 URL 时注意 +、/ 和 =。"
        ),
        BuiltInFunctionDoc(
            "编码",
            "base64url(value)",
            "{{base64url(token)}}",
            "URL / Query / Path",
            "URL 安全的 Base64，不带 padding，适合放在 URL 或文件名里。"
        ),
        BuiltInFunctionDoc(
            "编码",
            "base64Decode(value)",
            "{{base64Decode(encoded)}}",
            "所有模板位置",
            "标准 Base64 解码为 UTF-8 文本。"
        ),
        BuiltInFunctionDoc(
            "编码",
            "base64ToHex(value)",
            "{{base64ToHex(rsaEncrypt(secret, rsaPublicKey))}}",
            "所有模板位置",
            "先把 Base64 解码成原始字节，再转成小写 hex。RSA 密文转 hex 用它，不要用 hex()。"
        ),
        BuiltInFunctionDoc(
            "编码",
            "hexToBase64(value)",
            "{{hexToBase64(cipherHex)}}",
            "所有模板位置",
            "把 hex 解码成原始字节，再转成标准 Base64。"
        ),
        BuiltInFunctionDoc(
            "编码",
            "urlencode(value)",
            "{{urlencode(name)}}",
            "URL 文本",
            "RFC 3986 百分号编码，空格变成 %20。Query 参数值由 URL 构建器自动编码，通常不要重复调用。"
        ),
        BuiltInFunctionDoc(
            "编码",
            "urlencodeForm(value)",
            "{{urlencodeForm(name)}}",
            "表单 / Query",
            "表单编码，空格变成 +；与 application/x-www-form-urlencoded 一致。"
        ),
        BuiltInFunctionDoc(
            "编码",
            "urldecode(value)",
            "{{urldecode(encoded)}}",
            "所有模板位置",
            "解码百分号编码或表单编码。"
        ),
        BuiltInFunctionDoc(
            "编码",
            "hex(value)",
            "{{hex(text)}}",
            "所有模板位置",
            "UTF-8 文本字节转小写 hex；不是对 Base64 字符转 hex，二进制数据请用 base64ToHex。"
        ),
        BuiltInFunctionDoc(
            "编码",
            "unhex(value)",
            "{{unhex(hexText)}}",
            "所有模板位置",
            "hex 转 UTF-8 文本；允许空白。"
        ),

        // Strings.
        BuiltInFunctionDoc(
            "字符串",
            "concat(value, ...)",
            "{{concat('Bearer ', token)}}",
            "所有模板位置",
            "按顺序拼接最多 32 个参数。"
        ),
        BuiltInFunctionDoc(
            "字符串",
            "lower(value)",
            "{{lower(userName)}}",
            "所有模板位置",
            "转小写。"
        ),
        BuiltInFunctionDoc(
            "字符串",
            "upper(value)",
            "{{upper(hexValue)}}",
            "所有模板位置",
            "转大写，例如把 hex 输出改成大写。"
        ),
        BuiltInFunctionDoc(
            "字符串",
            "reverse(value)",
            "{{reverse(password)}}",
            "所有模板位置",
            "按字符反转。旧版登录密码反转后应使用 ohDaveRsa。"
        ),
        BuiltInFunctionDoc(
            "字符串",
            "trim(value)",
            "{{trim(rawToken)}}",
            "所有模板位置",
            "去掉首尾空白。"
        ),
        BuiltInFunctionDoc(
            "字符串",
            "length(value)",
            "{{length(token)}}",
            "所有模板位置",
            "返回字符数。"
        ),
        BuiltInFunctionDoc(
            "字符串",
            "replace(value, target, replacement)",
            "{{replace(token, '-', '')}}",
            "所有模板位置",
            "把 target 替换成 replacement。"
        ),
        BuiltInFunctionDoc(
            "字符串",
            "default(value, fallback)",
            "{{default(maybeEmpty, 'guest')}}",
            "所有模板位置",
            "value 为空字符串时返回 fallback。"
        ),
        BuiltInFunctionDoc(
            "字符串",
            "substring(value, start[, end])",
            "{{substring(token, 0, 8)}}",
            "所有模板位置",
            "按字符下标截取；下标从 0 开始，end 不含。"
        ),
        BuiltInFunctionDoc(
            "字符串",
            "jsonEscape(value)",
            "{{jsonEscape(userName)}}",
            "JSON 字符串值",
            "转义反斜杠、双引号和换行；变量可能包含这些字符时使用。"
        ),

        // Hash/MAC.
        BuiltInFunctionDoc(
            "哈希/MAC",
            "md5(value)",
            "{{md5(payload)}}",
            "所有模板位置",
            "MD5 小写 hex，仅用于兼容旧接口，不适合安全场景。"
        ),
        BuiltInFunctionDoc(
            "哈希/MAC",
            "sha1(value)",
            "{{sha1(payload)}}",
            "所有模板位置",
            "SHA-1 小写 hex。"
        ),
        BuiltInFunctionDoc(
            "哈希/MAC",
            "sha256(value)",
            "{{sha256(payload)}}",
            "所有模板位置",
            "SHA-256 小写 hex。"
        ),
        BuiltInFunctionDoc(
            "哈希/MAC",
            "sha512(value)",
            "{{sha512(payload)}}",
            "所有模板位置",
            "SHA-512 小写 hex。"
        ),
        BuiltInFunctionDoc(
            "哈希/MAC",
            "hmacSha256(key, value)",
            "{{hmacSha256(hmacKey, payload)}}",
            "所有模板位置",
            "HMAC-SHA256，结果是小写 hex。"
        ),
        BuiltInFunctionDoc(
            "哈希/MAC",
            "hmacSha256Base64(key, value)",
            "{{hmacSha256Base64(hmacKey, payload)}}",
            "所有模板位置",
            "HMAC-SHA256 的原始字节转 Base64。"
        ),

        // RSA.
        BuiltInFunctionDoc(
            "RSA",
            "rsaEncrypt(value, publicKeyPem)",
            "{{rsaEncrypt(secret, rsaPublicKey)}}",
            "Body / Header；密钥建议环境变量",
            "RSA/OAEP-SHA256 加密，输出标准 Base64。PEM 支持 PKCS#8/X.509 和 PKCS#1。"
        ),
        BuiltInFunctionDoc(
            "RSA",
            "rsaEncryptPkcs1(value, publicKeyPem)",
            "{{rsaEncryptPkcs1(secret, rsaPublicKey)}}",
            "Body / Header；密钥建议环境变量",
            "RSA/PKCS#1 v1.5 加密，输出标准 Base64；兼容使用 JSEncrypt 的旧接口。"
        ),
        BuiltInFunctionDoc(
            "RSA",
            "rsaDecrypt(base64Cipher, privateKeyPem)",
            "{{rsaDecrypt(cipher, rsaPrivateKey)}}",
            "Body / Header；私钥不要提交",
            "OAEP-SHA256 解密，输出 UTF-8 文本。私钥建议只放在本地环境变量。"
        ),
        BuiltInFunctionDoc(
            "RSA",
            "rsaDecryptPkcs1(base64Cipher, privateKeyPem)",
            "{{rsaDecryptPkcs1(cipher, rsaPrivateKey)}}",
            "Body / Header；私钥不要提交",
            "PKCS#1 v1.5 解密。"
        ),
        BuiltInFunctionDoc(
            "RSA",
            "rsaSign(value, privateKeyPem)",
            "{{rsaSign(payload, rsaPrivateKey)}}",
            "Header / Body；私钥不要提交",
            "SHA256withRSA 签名，输出 Base64。"
        ),
        BuiltInFunctionDoc(
            "RSA",
            "rsaVerify(value, base64Signature, publicKeyPem)",
            "{{rsaVerify(payload, signature, rsaPublicKey)}}",
            "Body / Header",
            "SHA256withRSA 验签，返回字符串 true 或 false。"
        ),

        // Legacy login.
        BuiltInFunctionDoc(
            "旧版登录",
            "ohDaveRsa(value, exponent, modulus)",
            "{{ohDaveRsa(reverse(password), exponent, modulus)}}",
            "user_ag / f2eweb 登录 Body",
            "旧版 OhDave 分块 raw RSA + hex，不是 PKCS#1/OAEP。key 用 rsaKey 响应的 exponent/modulus，不要用 encoded。"
        )
    )
}
