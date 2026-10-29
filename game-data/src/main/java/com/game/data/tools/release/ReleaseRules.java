package com.game.data.tools.release;

import static com.game.data.tools.release.ReleaseFailure.require;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** 版本与说明是离线发布原件的一部分；不读或创建 Git tag。 */
final class ReleaseRules {
    private static final Pattern VERSION = Pattern.compile("v(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(-[0-9A-Za-z-]+(\\.[0-9A-Za-z-]+)*)?");
    private ReleaseRules() { }
    static boolean version(String value) {
        return value != null && value.length() <= 115 && VERSION.matcher(value).matches();
    }
    static String notes(String changelog, String version) {
        require(version(version), "VERSION_INVALID");
        String heading = "## [" + version.substring(1) + "]";
        List<String> body = new ArrayList<>();
        boolean selected = false;
        int matches = 0;
        for (String line : changelog.replace("\r\n", "\n").split("\n", -1)) {
            if (line.startsWith("## ")) {
                selected = line.equals(heading) || line.startsWith(heading + " - ");
                if (selected) matches++;
            } else if (selected) body.add(line);
        }
        require(matches == 1, "CHANGELOG_SECTION");
        require(body.stream().anyMatch(line -> !line.isBlank() && !line.startsWith("#")
                && !line.matches("\\s*\\[[^]]+]:.*")), "CHANGELOG_EMPTY");
        return heading + "\n" + String.join("\n", body).strip() + "\n";
    }
}
