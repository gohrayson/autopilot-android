package app.autopilot.android;

import java.util.Set;

/** Identity policy shared by the Android archive adapter and host-side fixtures. */
public final class UpdateIdentity {
    public static final String PACKAGE_NAME = "app.autopilot.android";
    private UpdateIdentity() { }
    public static void verify(UpdateManifest release, String installedPackage, long installedVersion,
                              String archivePackage, long archiveVersion, String archiveVersionName,
                              int archiveMinSdk, int deviceSdk, Set<String> installedSigners,
                              Set<String> archiveSigners) throws UpdateDownload.UpdateException {
        if (!PACKAGE_NAME.equals(installedPackage) || !PACKAGE_NAME.equals(archivePackage)
                || !PACKAGE_NAME.equals(release.packageName)) fail("UPDATE_PACKAGE_MISMATCH");
        if (archiveVersion != release.versionCode || archiveVersion <= installedVersion
                || !release.versionName.equals(archiveVersionName)) fail("UPDATE_VERSION_MISMATCH");
        if (archiveMinSdk != release.minSdk || archiveMinSdk > deviceSdk) fail("UPDATE_ANDROID_UNSUPPORTED");
        if (installedSigners == null || archiveSigners == null || installedSigners.isEmpty()
                || archiveSigners.isEmpty() || !installedSigners.equals(archiveSigners)
                || installedSigners.stream().anyMatch(s -> s == null || !s.matches("[0-9a-f]{64}")))
            fail("UPDATE_SIGNER_MISMATCH");
    }
    private static void fail(String code) throws UpdateDownload.UpdateException {
        throw new UpdateDownload.UpdateException(code);
    }
}
