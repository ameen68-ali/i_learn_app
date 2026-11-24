package com.ilearn.app.net

/** Validate before passing a credential to an HTTP library that may describe bad header values. */
internal fun requireApiKey(key: String) {
    require(key.length in 1..4096 && key.all { it.code in 33..126 }) {
        "Enter only the API key, without spaces, a Bearer prefix or line breaks"
    }
}
