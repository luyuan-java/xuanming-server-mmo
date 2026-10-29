package com.game.data.tools.release;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.data.tools.ReleaseArtifacts;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReleaseBundleTest {
    @TempDir Path root;
    ReleaseFixture fixture;
    @BeforeEach void fixture() throws Exception { fixture = new ReleaseFixture(root); }

    @Test void createAndPinnedVerifyKeepProductionUnknown() throws Exception {
        var result = fixture.create();
        assertThat(result.offline()).isEqualTo("VERIFIED");
        assertThat(result.productionPreflight()).isEqualTo("UNKNOWN");
        assertThat(result.notCovered()).contains("reproducible_build_or_signed_provenance", "live_sql_schema_and_route_generation");
        assertThat(new ReleaseBundle().verify(fixture.bundle(), result.manifestSha256(), "release1")).isEqualTo(result);
        ReleaseManifest manifest = ReleaseJson.read(fixture.bundle().resolve(ReleaseBundle.MANIFEST), ReleaseManifest.class);
        assertThat(manifest.jarBuildInfo()).hasSize(17);
        assertThat(manifest.migrationDocumentHead()).isEqualTo("M14");
        assertThat(manifest.jarBuildInfo().get("game-data")).containsEntry("build.release", "unavailable");
        assertThat(manifest.files()).containsKey("source/config-data/navmesh/current_maps/navmesh.bin");
        assertThat(Files.readString(fixture.bundle().resolve("RELEASE_NOTES.md"))).contains("离线原件工具").doesNotContain("旧版。");
    }

    @Test void duplicateVersionNeverRewritesOriginal() throws Exception {
        var result = fixture.create();
        byte[] original = Files.readAllBytes(fixture.bundle().resolve(ReleaseBundle.MANIFEST));
        assertThatThrownBy(fixture::create).isInstanceOf(ReleaseFailure.class).hasMessage("VERSION_EXISTS");
        assertThat(Files.readAllBytes(fixture.bundle().resolve(ReleaseBundle.MANIFEST))).isEqualTo(original);
        assertThat(fixture.tool().verify(fixture.bundle(), result.manifestSha256(), "release1").offline()).isEqualTo("VERIFIED");
    }

    @Test void concurrentCreatorsCannotShareOneVersionDirectory() throws Exception {
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Boolean> create = () -> {
                try { fixture.create(); return true; }
                catch (java.nio.file.FileAlreadyExistsException occupied) { return false; }
                catch (ReleaseFailure occupied) {
                    assertThat(occupied.code()).isEqualTo("VERSION_EXISTS");
                    return false;
                }
            };
            var results = executor.invokeAll(java.util.List.of(create, create), 20, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(java.util.List.of(results.get(0).get(), results.get(1).get())).containsExactlyInAnyOrder(true, false);
            Path manifest = fixture.bundle().resolve(ReleaseBundle.MANIFEST);
            assertThat(fixture.tool().verify(fixture.bundle(), ReleaseFiles.hash(manifest, 8 * 1024 * 1024), "release1").offline()).isEqualTo("VERIFIED");
        }
    }

    @Test void interruptedCreateLeavesUnsealedOccupiedVersion() throws Exception {
        ReleaseBundle failing = new ReleaseBundle(new ReleaseSource.Repository() {
            @Override public ReleaseSource.Snapshot snapshot(Path path, String commit) { return fixture.snapshot(); }
            @Override public void unchanged(Path path, ReleaseSource.Snapshot snapshot) { throw new ReleaseFailure("SOURCE_CHANGED"); }
        });
        assertThatThrownBy(() -> failing.create(fixture.source, fixture.specFile, fixture.output, "release1"))
                .isInstanceOf(ReleaseFailure.class).hasMessage("SOURCE_CHANGED");
        assertThat(fixture.bundle()).isDirectory();
        assertThat(fixture.bundle().resolve(ReleaseBundle.MANIFEST)).doesNotExist();
        assertThatThrownBy(() -> fixture.tool().verify(fixture.bundle(), "a".repeat(64), "release1")).isInstanceOf(ReleaseFailure.class);
        assertThatThrownBy(fixture::create).hasMessage("VERSION_EXISTS");
    }

    @Test void wrongPinAndNamespaceReject() throws Exception {
        var result = fixture.create();
        assertThatThrownBy(() -> fixture.tool().verify(fixture.bundle(), "a".repeat(64), "release1")).hasMessage("MANIFEST_HASH_MISMATCH");
        assertThatThrownBy(() -> fixture.tool().verify(fixture.bundle(), null, "release1")).hasMessage("MANIFEST_PIN_REQUIRED");
        assertThatThrownBy(() -> fixture.tool().verify(fixture.bundle(), result.manifestSha256(), "other1")).hasMessage("NAMESPACE_MISMATCH");
    }

    @Test void changedPayloadAndExtraFileReject() throws Exception {
        var result = fixture.create();
        Files.writeString(fixture.bundle().resolve("unexpected.txt"), "extra");
        assertThatThrownBy(() -> fixture.tool().verify(fixture.bundle(), result.manifestSha256(), "release1")).hasMessage("BUNDLE_FILE_SET");
        Files.delete(fixture.bundle().resolve("unexpected.txt"));
        Path table = fixture.bundle().resolve("source/config-data/tables/table.json");
        Files.writeString(table, "{}\n");
        assertThatThrownBy(() -> fixture.tool().verify(fixture.bundle(), result.manifestSha256(), "release1")).hasMessage("FILE_HASH_MISMATCH");
    }

    @Test void checksumTextMustBeExact() throws Exception {
        var result = fixture.create();
        Path sums = fixture.bundle().resolve(ReleaseBundle.SUMS);
        Files.writeString(sums, Files.readString(sums).replace("\n", "\r\n"));
        assertThatThrownBy(() -> fixture.tool().verify(fixture.bundle(), result.manifestSha256(), "release1"))
                .hasMessage("CHECKSUM_LIST_MISMATCH");
    }

    @Test void truncatedOrReformattedManifestCannotPass() throws Exception {
        fixture.create();
        Path manifest = fixture.bundle().resolve(ReleaseBundle.MANIFEST);
        String json = Files.readString(manifest);
        Files.writeString(manifest, json.substring(0, json.length() / 2));
        assertThatThrownBy(() -> fixture.tool().verify(fixture.bundle(), ReleaseFiles.hash(manifest, 8 * 1024 * 1024), "release1"))
                .hasMessage("JSON_INVALID");
        Files.writeString(manifest, " " + json);
        assertThatThrownBy(() -> fixture.tool().verify(fixture.bundle(), ReleaseFiles.hash(manifest, 8 * 1024 * 1024), "release1"))
                .hasMessage("MANIFEST_NOT_CANONICAL");
    }

    @Test void outputCannotLiveInsideSource() throws Exception {
        Path output = Files.createDirectory(fixture.source.resolve("out"));
        assertThatThrownBy(() -> fixture.tool().create(fixture.source, fixture.specFile, output, "release1")).hasMessage("OUTPUT_ROOT_INVALID");
        assertThat(output.resolve(fixture.spec.version())).doesNotExist();
    }

    @Test void missingServiceSourceIsNotAccepted() throws Exception {
        fixture.sources.remove("game-mail/pom.xml");
        assertThatThrownBy(fixture::create).hasMessage("BOOT_MODULE_DRIFT");
        assertThat(fixture.bundle()).doesNotExist();
    }

    @Test void mismatchedContractAndMapsClaimCannotCreateBundle() throws Exception {
        fixture.add("contract/SOURCE.properties", "mmorpg.commit=" + ReleaseFixture.CONTRACT_COMMIT + "\nmaps.source.commit=" + "f".repeat(40) + "\n");
        assertThatThrownBy(fixture::create).hasMessage("SOURCE_CONTRACT_MISMATCH");
        assertThat(fixture.bundle()).doesNotExist();
        fixture.add("contract/SOURCE.properties", "mmorpg.commit=" + "f".repeat(40) + "\nmaps.source.commit=" + ReleaseFixture.MAPS_COMMIT + "\n");
        assertThatThrownBy(fixture::create).hasMessage("SOURCE_CONTRACT_MISMATCH");
    }

    @Test void cliVerifyIsZeroButPreflightIsThreeAndNeverLeaksInputs() throws Exception {
        var result = fixture.create();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        String[] args = {"verify", "--bundle", fixture.bundle().toString(), "--manifest-sha256", result.manifestSha256()};
        Map<String, String> environment = Map.of("GAME_NAMESPACE", "release1");
        assertThat(ReleaseArtifacts.run(args, Map.of(), environment, new PrintStream(out), new PrintStream(err))).isZero();
        args[0] = "preflight";
        assertThat(ReleaseArtifacts.run(args, Map.of(), environment, new PrintStream(out), new PrintStream(err))).isEqualTo(3);
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("\"productionPreflight\":\"UNKNOWN\"");
        args[4] = "do-not-log-secret";
        assertThat(ReleaseArtifacts.run(args, Map.of(), environment, new PrintStream(out), new PrintStream(err))).isEqualTo(1);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("MANIFEST_PIN_REQUIRED").doesNotContain("do-not-log-secret");
        assertThat(ReleaseArtifacts.run(new String[]{"verify"}, Map.of(), environment, new PrintStream(out), new PrintStream(err))).isEqualTo(2);
    }
}
