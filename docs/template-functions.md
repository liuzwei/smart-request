# Template functions

Requests can transform variables before they are sent. The syntax is a function call inside the
usual double braces:

```json
{
  "token": "{{base64(token)}}",
  "signature": "{{rsaSign(payload, rsaPrivateKey)}}"
}
```

Functions are available in the URL, query parameters, path parameters, headers and body, and in
environment variable values. Scenario runs resolve them against the run scope, so a value captured
by an upstream step can be transformed before the next request is sent.

## Syntax

```
{{base64(token)}}
{{base64url(token)}}
{{urlencode(base64(token))}}
{{concat('Bearer ', base64(token))}}
{{base64 ( token )}}
```

- A bare name is a variable: `{{token}}`.
- A name followed by `(` is a function call: `{{base64(token)}}`.
- Function names are case-insensitive; variable names are case-sensitive.
- Arguments may be variables, nested calls, or string literals. Use single quotes for literals so a
  JSON body does not need escaped double quotes: `{{concat('Bearer ', token)}}`.
- Supported string escapes: `\\`, `\'`, `\"`, `\n`, `\r`, `\t`, `\b`, `\f`, `\uXXXX`.
- Number-looking arguments such as `0` and `10` are passed as text; functions that need a number
  parse them (`substring(value, 0, 5)`).
- A function result is final text and is not scanned again for `{{...}}`; a variable whose value is
  itself a template is resolved recursively.
- A missing variable is reported by name (`Unresolved variables: token`) and prevents the request
  from being sent. Unknown functions, wrong argument counts and syntax errors also fail before the
  request is sent.
- Functions are pure: no file, network, environment, clock or random access. The same arguments
  produce the same result (RSA encryption is the one intentional exception because OAEP uses a
  random seed).

## Built-in functions

### Encoding

| Function | Result |
|---|---|
| `base64(value)` | Standard Base64, UTF-8 |
| `base64url(value)` | URL-safe Base64 without padding |
| `base64Decode(value)` | Decode standard Base64 to UTF-8 text |
| `urlencode(value)` | Percent-encode (space becomes `%20`) |
| `urlencodeForm(value)` | Form encoding (space becomes `+`) |
| `urldecode(value)` | Decode percent/form encoding |
| `hex(value)` | UTF-8 bytes as lowercase hex |
| `unhex(value)` | Hex to UTF-8 text (whitespace allowed) |

### Hashing and MAC

| Function | Result |
|---|---|
| `md5(value)` | MD5 hex |
| `sha1(value)` | SHA-1 hex |
| `sha256(value)` | SHA-256 hex |
| `sha512(value)` | SHA-512 hex |
| `hmacSha256(key, value)` | HMAC-SHA256 hex |
| `hmacSha256Base64(key, value)` | HMAC-SHA256 raw bytes as Base64 |

### Strings

`concat(...)` (up to 32 arguments), `lower`, `upper`, `trim`, `length`, `replace(value, target,
replacement)`, `default(value, fallback)`, `substring(value, start[, end])`, `jsonEscape(value)`.

`jsonEscape` escapes `\`, `"` and control characters; use it when a variable is placed inside a JSON
string and may contain those characters:

```json
{"name":"{{jsonEscape(userName)}}"}
```

### RSA

| Function | Result |
|---|---|
| `rsaEncrypt(value, publicKey)` | RSA/OAEP-SHA256 ciphertext, Base64 |
| `rsaEncryptPkcs1(value, publicKey)` | RSA/PKCS#1 v1.5 ciphertext, Base64 |
| `rsaDecrypt(base64Cipher, privateKey)` | OAEP-SHA256 plaintext |
| `rsaDecryptPkcs1(base64Cipher, privateKey)` | PKCS#1 v1.5 plaintext |
| `rsaSign(value, privateKey)` | SHA256withRSA signature, Base64 |
| `rsaVerify(value, base64Signature, publicKey)` | `true` or `false` |

Keys are PEM strings or raw Base64 DER. Accepted PEM types:

- `PUBLIC KEY` / `PRIVATE KEY` (X.509 / PKCS#8, the modern OpenSSL defaults);
- `RSA PUBLIC KEY` / `RSA PRIVATE KEY` (the older PKCS#1 forms; wrapped internally);
- encrypted private keys are rejected — decrypt them first.

Keys are long, so put them in environment variables instead of inlining them in every request:

```text
Name: rsaPublicKey
Value: -----BEGIN PUBLIC KEY-----
       MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8A...
       -----END PUBLIC KEY-----
```

Then:

```json
{"payload":"{{rsaEncrypt(secret, rsaPublicKey)}}"}
```

RSA key sizes 1024–8192 bits are accepted. OAEP uses SHA-256 with MGF1-SHA256. Signing and
verification use SHA-256. Output is standard Base64.

**Security notes**

- Base64, URL encoding and hex are encodings, not encryption; do not use them to protect secrets.
- Private keys in `.idea/smart-request.xml` or environment variables are stored in plain text.
  Prefer local environment variables and never commit private keys.
- RSA encryption protects confidentiality but does not authenticate the sender; use `rsaSign` when
  the receiver needs to verify origin.
- The plugin does not log function arguments. Request history still contains the raw template, not
  the resolved values.

## Examples

Base64 a captured token in a JSON body:

```json
{"token":"{{base64(token)}}"}
```

Bearer token composed from a captured value:

```json
{"authorization":"{{concat('Bearer ', token)}}"}
```

RSA-encrypt a password with a public key from the active environment:

```json
{"password":"{{rsaEncrypt(password, rsaPublicKey)}}"}
```

Sign a canonical payload and send the signature in a header:

```text
X-Signature: {{rsaSign(payload, rsaPrivateKey)}}
```

Hash a request body with HMAC:

```json
{"digest":"{{hmacSha256(hmacKey, payload)}}"}
```

## Where functions run

| Location | Resolved by |
|---|---|
| URL, query keys/values | `RequestUrlBuilder` |
| Path parameter values | `PathParameterResolver` |
| Header keys/values, body | `RequestPreparer` |
| Environment variable values | recursive resolution when referenced |
| Scenario data-flow validation | `TemplateEngine.analyze` extracts the variables inside calls |

The scenario validator treats `{{base64(token)}}` as a dependency on `token`, not on a variable
named `base64(token)`, so an upstream capture is recognized correctly.

## Limitations

- Custom pure-macro definitions are a separate next step; the built-in catalog currently covers the
  common encodings, hashes, HMAC and RSA operations.
- No loops, conditions, arithmetic or arbitrary scripting.
- A function result is inserted as text. When placing a value inside a JSON string, quote it and use
  `jsonEscape` if the value may contain quotes or backslashes. Base64 and hex results are safe.
- `urlencode` is for text contexts. Query parameter values are encoded by the URL builder, so do not
  call `urlencode` on a query parameter value unless you intentionally want a double-encoded value.
