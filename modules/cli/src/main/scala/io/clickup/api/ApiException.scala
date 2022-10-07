package io.clickup.api

enum ApiException extends RuntimeException {
  case InvalidAuth(token: String)
  case Unknown(cause: String, httpStatusCode: Int)
}
