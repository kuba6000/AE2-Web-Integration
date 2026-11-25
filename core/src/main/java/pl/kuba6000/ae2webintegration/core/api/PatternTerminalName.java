package pl.kuba6000.ae2webintegration.core.api;

import com.google.gson.annotations.SerializedName;

/** In-game name of the terminal that exposes pattern providers. */
public enum PatternTerminalName {
    /** Interface Terminal on legacy versions. */
    @SerializedName("interface_terminal")
    INTERFACE_TERMINAL,
    /** Pattern Access Terminal on modern versions. */
    @SerializedName("pattern_access_terminal")
    PATTERN_ACCESS_TERMINAL
}
