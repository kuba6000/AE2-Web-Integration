package pl.kuba6000.ae2webintegration.core.http.contract;

/** Whether a route requires, optionally uses, or ignores existing request credentials. */
public enum Authentication {
    REQUIRED,
    OPTIONAL,
    NONE
}
