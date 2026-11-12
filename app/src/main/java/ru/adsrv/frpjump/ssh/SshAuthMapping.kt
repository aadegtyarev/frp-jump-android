package ru.adsrv.frpjump.ssh

/**
 * The login of [this] method: the app's key, or a password. An empty [password] means "the remembered one";
 * [remember] says whether a successful password login is kept.
 */
fun SshAuthMethod.toAuth(password: String = "", remember: Boolean = false): SshAuth = when (this) {
    SshAuthMethod.KEY -> SshAuth.Key
    SshAuthMethod.PASSWORD -> SshAuth.Password(password.ifEmpty { null }, remember)
}
