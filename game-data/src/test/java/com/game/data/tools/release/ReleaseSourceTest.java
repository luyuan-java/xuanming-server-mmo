package com.game.data.tools.release;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 只在临时仓库验证真实 Git 读取；不修改用户仓库、全局配置、tag 或远端。 */
class ReleaseSourceTest {
    @TempDir Path root;

    @Test void cleanTrackedFilesAreBoundToGitBlobs() throws Exception {
        ReleaseFixture fixture = repository();
        String commit = git(fixture.source, "rev-parse", "HEAD").strip();
        ReleaseSource.Snapshot snapshot = ReleaseSource.GIT.snapshot(fixture.source, commit);
        assertThat(snapshot.commit()).isEqualTo(commit);
        assertThat(snapshot.files()).hasSameSizeAs(fixture.sources);
        assertThat(snapshot.blobs().get("CHANGELOG.md")).isEqualTo(ReleaseFiles.gitBlob(fixture.sources.get("CHANGELOG.md")));
        assertThat(snapshot.files().keySet()).noneMatch(path -> path.contains("/target/"));
        ReleaseSource.GIT.unchanged(fixture.source, snapshot);
        assertThat(git(fixture.source, "status", "--porcelain=v1")).isEmpty();
    }

    @Test void modifiedAndUntrackedSourceAreRejected() throws Exception {
        ReleaseFixture fixture = repository();
        String commit = git(fixture.source, "rev-parse", "HEAD").strip();
        byte[] before = Files.readAllBytes(fixture.source.resolve("CHANGELOG.md"));
        Files.writeString(fixture.source.resolve("CHANGELOG.md"), "dirty\n");
        assertThatThrownBy(() -> ReleaseSource.GIT.snapshot(fixture.source, commit)).hasMessage("SOURCE_DIRTY");
        Files.write(fixture.source.resolve("CHANGELOG.md"), before);
        Files.writeString(fixture.source.resolve("untracked.txt"), "dirty\n");
        assertThatThrownBy(() -> ReleaseSource.GIT.snapshot(fixture.source, commit)).hasMessage("SOURCE_DIRTY");
    }

    @Test void wrongCommitSubdirectoryAndConcurrentSourceChangeReject() throws Exception {
        ReleaseFixture fixture = repository();
        String commit = git(fixture.source, "rev-parse", "HEAD").strip();
        assertThatThrownBy(() -> ReleaseSource.GIT.snapshot(fixture.source, "0".repeat(40))).hasMessage("SOURCE_COMMIT_MISMATCH");
        assertThatThrownBy(() -> ReleaseSource.GIT.snapshot(fixture.source.resolve("contract"), commit)).hasMessage("SOURCE_NOT_REPOSITORY_ROOT");
        ReleaseSource.Snapshot before = ReleaseSource.GIT.snapshot(fixture.source, commit);
        Files.writeString(fixture.source.resolve("CHANGELOG.md"), "changed\n");
        assertThatThrownBy(() -> ReleaseSource.GIT.unchanged(fixture.source, before)).hasMessage("SOURCE_DIRTY");
    }

    @Test void sourcePolicyIncludesContractMapsSchemasAndApplicationDefaults() {
        for (String path : List.of("game-proto/src/main/resources/contract/manifest.json", "game-data/src/main/proto/store.proto",
                "game-mall/src/main/resources/db/migration/V5__upgrade.sql", "config-data/navmesh/current_maps/a.bin",
                "game-gate/src/main/resources/application.yaml", "game-inbox/src/main/resources/application-prod.yaml")) {
            assertThat(ReleaseSource.included(path)).as(path).isTrue();
        }
        assertThat(ReleaseSource.included("game-data/target/classes/application.yaml")).isFalse();
        assertThat(ReleaseSource.included("secrets.env")).isFalse();
    }

    @Test void assumeUnchangedAndSkipWorktreeCannotHideDirtySource() throws Exception {
        ReleaseFixture fixture = repository();
        String commit = git(fixture.source, "rev-parse", "HEAD").strip();
        git(fixture.source, "update-index", "--assume-unchanged", "CHANGELOG.md");
        Files.writeString(fixture.source.resolve("CHANGELOG.md"), "hidden dirty\n");
        assertThatThrownBy(() -> ReleaseSource.GIT.snapshot(fixture.source, commit)).hasMessage("SOURCE_INDEX_FLAGS");
        git(fixture.source, "update-index", "--no-assume-unchanged", "CHANGELOG.md");
        git(fixture.source, "update-index", "--skip-worktree", "CHANGELOG.md");
        assertThatThrownBy(() -> ReleaseSource.GIT.snapshot(fixture.source, commit)).hasMessage("SOURCE_INDEX_FLAGS");
    }

    @Test void pomParserRejectsExternalEntityInput() {
        byte[] malicious = "<!DOCTYPE project [<!ENTITY x SYSTEM 'file:///not-read'>]><project><version>&x;</version></project>"
                .getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> ReleaseSource.mavenVersion(malicious)).hasMessage("POM_INVALID");
    }

    private ReleaseFixture repository() throws Exception {
        ReleaseFixture fixture = new ReleaseFixture(root);
        Files.createDirectories(root.resolve("empty-hooks"));
        Files.writeString(root.resolve("empty-git-config"), "");
        Files.writeString(fixture.source.resolve(".gitignore"), "**/target/\n");
        git(fixture.source, "init", "--quiet");
        git(fixture.source, "add", "--all");
        git(fixture.source, "commit", "--quiet", "-m", "离线发布测试夹具");
        return fixture;
    }

    private String git(Path source, String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "--no-optional-locks", "-c", "core.hooksPath=" + root.resolve("empty-hooks"),
                "-c", "commit.gpgsign=false", "-c", "user.name=release-test", "-c", "user.email=release-test@example.invalid",
                "-c", "core.autocrlf=false", "-c", "core.fsmonitor=false", "-C", source.toString()));
        command.addAll(List.of(arguments));
        ProcessBuilder builder = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD);
        builder.environment().keySet().removeIf(key -> key.startsWith("GIT_"));
        builder.environment().put("GIT_CONFIG_GLOBAL", root.resolve("empty-git-config").toString());
        builder.environment().put("GIT_CONFIG_SYSTEM", root.resolve("empty-git-config").toString());
        builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        Process process = builder.start();
        try {
            assertThat(process.waitFor(10, TimeUnit.SECONDS)).as("临时 Git 命令须有界结束").isTrue();
            assertThat(process.exitValue()).isZero();
            try (var input = process.getInputStream()) { return new String(input.readNBytes(65536), StandardCharsets.UTF_8); }
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }
}
