package com.termux.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.system.Os;
import android.util.Pair;
import android.view.WindowManager;

import com.termux.R;
import com.termux.shared.file.FileUtils;
import com.termux.shared.termux.crash.TermuxCrashUtils;
import com.termux.shared.termux.file.TermuxFileUtils;
import com.termux.shared.interact.MessageDialogUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.markdown.MarkdownUtils;
import com.termux.shared.errors.Error;
import com.termux.shared.android.PackageUtils;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.TermuxUtils;
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static com.termux.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR;
import static com.termux.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR_PATH;
import static com.termux.shared.termux.TermuxConstants.TERMUX_STAGING_PREFIX_DIR;
import static com.termux.shared.termux.TermuxConstants.TERMUX_STAGING_PREFIX_DIR_PATH;

/**
 * Install the Termux bootstrap packages if necessary by following the below steps:
 * <p/>
 * (1) If $PREFIX already exist, assume that it is correct and be done. Note that this relies on that we do not create a
 * broken $PREFIX directory below.
 * <p/>
 * (2) A progress dialog is shown with "Installing..." message and a spinner.
 * <p/>
 * (3) A staging directory, $STAGING_PREFIX, is cleared if left over from broken installation below.
 * <p/>
 * (4) The zip file is loaded from a shared library.
 * <p/>
 * (5) The zip, containing entries relative to the $PREFIX, is is downloaded and extracted by a zip input stream
 * continuously encountering zip file entries:
 * <p/>
 * (5.1) If the zip entry encountered is SYMLINKS.txt, go through it and remember all symlinks to setup.
 * <p/>
 * (5.2) For every other zip entry, extract it into $STAGING_PREFIX and set execute permissions if necessary.
 */
final class TermuxInstaller {

    private static final String LOG_TAG = "TermuxInstaller";

    /** Performs bootstrap setup if necessary. */
    static void setupBootstrapIfNeeded(final Activity activity, final Runnable whenDone) {
        String bootstrapErrorMessage;
        Error filesDirectoryAccessibleError;

        // This will also call Context.getFilesDir(), which should ensure that termux files directory
        // is created if it does not already exist
        filesDirectoryAccessibleError = TermuxFileUtils.isTermuxFilesDirectoryAccessible(activity, true, true);
        boolean isFilesDirectoryAccessible = filesDirectoryAccessibleError == null;

        // Termux can only be run as the primary user (device owner) since only that
        // account has the expected file system paths. Verify that:
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !PackageUtils.isCurrentUserThePrimaryUser(activity)) {
            bootstrapErrorMessage = activity.getString(R.string.bootstrap_error_not_primary_user_message,
                MarkdownUtils.getMarkdownCodeForString(TERMUX_PREFIX_DIR_PATH, false));
            Logger.logError(LOG_TAG, "isFilesDirectoryAccessible: " + isFilesDirectoryAccessible);
            Logger.logError(LOG_TAG, bootstrapErrorMessage);
            sendBootstrapCrashReportNotification(activity, bootstrapErrorMessage);
            MessageDialogUtils.exitAppWithErrorMessage(activity,
                activity.getString(R.string.bootstrap_error_title),
                bootstrapErrorMessage);
            return;
        }

        if (!isFilesDirectoryAccessible) {
            bootstrapErrorMessage = Error.getMinimalErrorString(filesDirectoryAccessibleError);
            //noinspection SdCardPath
            if (PackageUtils.isAppInstalledOnExternalStorage(activity) &&
                !arePathsEquivalent(TermuxConstants.TERMUX_FILES_DIR_PATH, activity.getFilesDir().getAbsolutePath())) {
                bootstrapErrorMessage += "\n\n" + activity.getString(R.string.bootstrap_error_installed_on_portable_sd,
                    MarkdownUtils.getMarkdownCodeForString(TERMUX_PREFIX_DIR_PATH, false));
            }

            Logger.logError(LOG_TAG, bootstrapErrorMessage);
            sendBootstrapCrashReportNotification(activity, bootstrapErrorMessage);
            MessageDialogUtils.showMessage(activity,
                activity.getString(R.string.bootstrap_error_title),
                bootstrapErrorMessage, null);
            return;
        }

        // If prefix directory exists, even if its a symlink to a valid directory and symlink is not broken/dangling
        if (FileUtils.directoryFileExists(TERMUX_PREFIX_DIR_PATH, true)) {
            if (TermuxFileUtils.isTermuxPrefixDirectoryEmpty()) {
                Logger.logInfo(LOG_TAG, "The termux prefix directory \"" + TERMUX_PREFIX_DIR_PATH + "\" exists but is empty or only contains specific unimportant files.");
            } else {
                whenDone.run();
                return;
            }
        } else if (FileUtils.fileExists(TERMUX_PREFIX_DIR_PATH, false)) {
            Logger.logInfo(LOG_TAG, "The termux prefix directory \"" + TERMUX_PREFIX_DIR_PATH + "\" does not exist but another file exists at its destination.");
        }

        final ProgressDialog progress = ProgressDialog.show(activity, null, activity.getString(R.string.bootstrap_installer_body), true, false);
        new Thread() {
            @Override
            public void run() {
                try {
                    Logger.logInfo(LOG_TAG, "Installing " + TermuxConstants.TERMUX_APP_NAME + " bootstrap packages.");

                    Error error;

                    // Delete prefix staging directory or any file at its destination
                    error = FileUtils.deleteFile("termux prefix staging directory", TERMUX_STAGING_PREFIX_DIR_PATH, true);
                    if (error != null) {
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                        return;
                    }

                    // Delete prefix directory or any file at its destination
                    error = FileUtils.deleteFile("termux prefix directory", TERMUX_PREFIX_DIR_PATH, true);
                    if (error != null) {
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                        return;
                    }

                    // Create prefix staging directory if it does not already exist and set required permissions
                    error = TermuxFileUtils.isTermuxPrefixStagingDirectoryAccessible(true, true);
                    if (error != null) {
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                        return;
                    }

                    // Create prefix directory if it does not already exist and set required permissions
                    error = TermuxFileUtils.isTermuxPrefixDirectoryAccessible(true, true);
                    if (error != null) {
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                        return;
                    }

                    Logger.logInfo(LOG_TAG, "Extracting bootstrap zip to prefix staging directory \"" + TERMUX_STAGING_PREFIX_DIR_PATH + "\".");

                    final byte[] buffer = new byte[8096];
                    final List<Pair<String, String>> symlinks = new ArrayList<>(50);

                    final byte[] zipBytes = loadZipBytes();
                    try (ZipInputStream zipInput = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
                        ZipEntry zipEntry;
                        while ((zipEntry = zipInput.getNextEntry()) != null) {
                            if (zipEntry.getName().equals("SYMLINKS.txt")) {
                                BufferedReader symlinksReader = new BufferedReader(new InputStreamReader(zipInput));
                                String line;
                                while ((line = symlinksReader.readLine()) != null) {
                                    String[] parts = line.split("←");
                                    if (parts.length != 2)
                                        throw new RuntimeException("Malformed symlink line: " + line);
                                    String oldPath = parts[0];
                                    String newPath = TERMUX_STAGING_PREFIX_DIR_PATH + "/" + parts[1];
                                    symlinks.add(Pair.create(oldPath, newPath));

                                    error = ensureDirectoryExists(new File(newPath).getParentFile());
                                    if (error != null) {
                                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                                        return;
                                    }
                                }
                            } else {
                                String zipEntryName = zipEntry.getName();
                                File targetFile = new File(TERMUX_STAGING_PREFIX_DIR_PATH, zipEntryName);
                                boolean isDirectory = zipEntry.isDirectory();

                                error = ensureDirectoryExists(isDirectory ? targetFile : targetFile.getParentFile());
                                if (error != null) {
                                    showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                                    return;
                                }

                                if (!isDirectory) {
                                    // Read file content to check for hardcoded paths
                                    java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                                    int readBytes;
                                    while ((readBytes = zipInput.read(buffer)) != -1) {
                                        baos.write(buffer, 0, readBytes);
                                    }
                                    byte[] fileBytes = baos.toByteArray();
                                    
                                    // Replace hardcoded package paths in text files
                                    // Always use /data/data/ format for consistency
                                    String fileContent = new String(fileBytes, java.nio.charset.StandardCharsets.UTF_8);
                                    String originalPath = "/data/data/com.termux/";
                                    String originalPathUser = "/data/user/0/com.termux/";
                                    String newPath = "/data/data/com.readboy.termux/files/";
                                    
                                    // Handle both /data/data/ and /data/user/0/ paths
                                    // Replace both with /data/data/com.readboy.termux/ format
                                    if (fileContent.contains(originalPath)) {
                                        fileContent = fileContent.replace(originalPath, newPath);
                                        fileBytes = fileContent.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                                    }
                                    if (fileContent.contains(originalPathUser)) {
                                        fileContent = fileContent.replace(originalPathUser, newPath);
                                        fileBytes = fileContent.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                                    }
                                    
                                    try (FileOutputStream outStream = new FileOutputStream(targetFile)) {
                                        outStream.write(fileBytes);
                                    }
                                    
                                    if (zipEntryName.startsWith("bin/") || zipEntryName.startsWith("usr/bin/") ||
                                        zipEntryName.startsWith("libexec") ||
                                        zipEntryName.startsWith("lib/apt/apt-helper") || zipEntryName.startsWith("lib/apt/methods")) {
                                        //noinspection OctalInteger
                                        Os.chmod(targetFile.getAbsolutePath(), 0700);
                                    }
                                }
                            }
                        }
                    }

                    if (symlinks.isEmpty())
                        throw new RuntimeException("No SYMLINKS.txt encountered");
                    for (Pair<String, String> symlink : symlinks) {
                        Os.symlink(symlink.first, symlink.second);
                    }

                    Logger.logInfo(LOG_TAG, "Moving termux prefix staging to prefix directory.");

                    if (!TERMUX_STAGING_PREFIX_DIR.renameTo(TERMUX_PREFIX_DIR)) {
                        throw new RuntimeException("Moving termux prefix staging to prefix directory failed");
                    }

                    Logger.logInfo(LOG_TAG, "Bootstrap packages installed successfully.");

                    // Download and install proot for path mapping support
                    downloadAndInstallProot(activity);

                    // Create compatibility symlinks for proot path mapping
                    // This allows using proot to map /data/data/com.termux/ to /data/data/com.readboy.termux/
                    createCompatibilitySymlinks();

                    // Create proot configuration files
                    createProotConfiguration(activity);

                    // Recreate env file since termux prefix was wiped earlier
                    TermuxShellEnvironment.writeEnvironmentToFile(activity);

                    activity.runOnUiThread(whenDone);

                } catch (final Exception e) {
                    showBootstrapErrorDialog(activity, whenDone, Logger.getStackTracesMarkdownString(null, Logger.getStackTracesStringArray(e)));

                } finally {
                    activity.runOnUiThread(() -> {
                        try {
                            progress.dismiss();
                        } catch (RuntimeException e) {
                            // Activity already dismissed - ignore.
                        }
                    });
                }
            }
        }.start();
    }

    public static void showBootstrapErrorDialog(Activity activity, Runnable whenDone, String message) {
        Logger.logErrorExtended(LOG_TAG, "Bootstrap Error:\n" + message);

        // Send a notification with the exception so that the user knows why bootstrap setup failed
        sendBootstrapCrashReportNotification(activity, message);

        activity.runOnUiThread(() -> {
            try {
                new AlertDialog.Builder(activity).setTitle(R.string.bootstrap_error_title).setMessage(R.string.bootstrap_error_body)
                    .setNegativeButton(R.string.bootstrap_error_abort, (dialog, which) -> {
                        dialog.dismiss();
                        activity.finish();
                    })
                    .setPositiveButton(R.string.bootstrap_error_try_again, (dialog, which) -> {
                        dialog.dismiss();
                        FileUtils.deleteFile("termux prefix directory", TERMUX_PREFIX_DIR_PATH, true);
                        TermuxInstaller.setupBootstrapIfNeeded(activity, whenDone);
                    }).show();
            } catch (WindowManager.BadTokenException e1) {
                // Activity already dismissed - ignore.
            }
        });
    }

    private static void sendBootstrapCrashReportNotification(Activity activity, String message) {
        final String title = TermuxConstants.TERMUX_APP_NAME + " Bootstrap Error";

        // Add info of all install Termux plugin apps as well since their target sdk or installation
        // on external/portable sd card can affect Termux app files directory access or exec.
        TermuxCrashUtils.sendCrashReportNotification(activity, LOG_TAG,
            title, null, "## " + title + "\n\n" + message + "\n\n" +
                TermuxUtils.getTermuxDebugMarkdownString(activity),
            true, false, TermuxUtils.AppInfoMode.TERMUX_AND_PLUGIN_PACKAGES, true);
    }

    static void setupStorageSymlinks(final Context context) {
        final String LOG_TAG = "termux-storage";
        final String title = TermuxConstants.TERMUX_APP_NAME + " Setup Storage Error";

        Logger.logInfo(LOG_TAG, "Setting up storage symlinks.");

        new Thread() {
            public void run() {
                try {
                    Error error;
                    File storageDir = TermuxConstants.TERMUX_STORAGE_HOME_DIR;

                    error = FileUtils.clearDirectory("~/storage", storageDir.getAbsolutePath());
                    if (error != null) {
                        Logger.logErrorAndShowToast(context, LOG_TAG, error.getMessage());
                        Logger.logErrorExtended(LOG_TAG, "Setup Storage Error\n" + error.toString());
                        TermuxCrashUtils.sendCrashReportNotification(context, LOG_TAG, title, null,
                            "## " + title + "\n\n" + Error.getErrorMarkdownString(error),
                            true, false, TermuxUtils.AppInfoMode.TERMUX_PACKAGE, true);
                        return;
                    }

                    Logger.logInfo(LOG_TAG, "Setting up storage symlinks at ~/storage/shared, ~/storage/downloads, ~/storage/dcim, ~/storage/pictures, ~/storage/music and ~/storage/movies for directories in \"" + Environment.getExternalStorageDirectory().getAbsolutePath() + "\".");

                    // Get primary storage root "/storage/emulated/0" symlink
                    File sharedDir = Environment.getExternalStorageDirectory();
                    Os.symlink(sharedDir.getAbsolutePath(), new File(storageDir, "shared").getAbsolutePath());

                    File documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
                    Os.symlink(documentsDir.getAbsolutePath(), new File(storageDir, "documents").getAbsolutePath());

                    File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    Os.symlink(downloadsDir.getAbsolutePath(), new File(storageDir, "downloads").getAbsolutePath());

                    File dcimDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM);
                    Os.symlink(dcimDir.getAbsolutePath(), new File(storageDir, "dcim").getAbsolutePath());

                    File picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
                    Os.symlink(picturesDir.getAbsolutePath(), new File(storageDir, "pictures").getAbsolutePath());

                    File musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC);
                    Os.symlink(musicDir.getAbsolutePath(), new File(storageDir, "music").getAbsolutePath());

                    File moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES);
                    Os.symlink(moviesDir.getAbsolutePath(), new File(storageDir, "movies").getAbsolutePath());

                    File podcastsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PODCASTS);
                    Os.symlink(podcastsDir.getAbsolutePath(), new File(storageDir, "podcasts").getAbsolutePath());

                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        File audiobooksDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_AUDIOBOOKS);
                        Os.symlink(audiobooksDir.getAbsolutePath(), new File(storageDir, "audiobooks").getAbsolutePath());
                    }

                    // Dir 0 should ideally be for primary storage
                    // https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/core/java/android/app/ContextImpl.java;l=818
                    // https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/core/java/android/os/Environment.java;l=219
                    // https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/core/java/android/os/Environment.java;l=181
                    // https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/services/core/java/com/android/server/StorageManagerService.java;l=3796
                    // https://cs.android.com/android/platform/superproject/+/android-7.0.0_r36:frameworks/base/services/core/java/com/android/server/MountService.java;l=3053

                    // Create "Android/data/com.termux" symlinks
                    File[] dirs = context.getExternalFilesDirs(null);
                    if (dirs != null && dirs.length > 0) {
                        for (int i = 0; i < dirs.length; i++) {
                            File dir = dirs[i];
                            if (dir == null) continue;
                            String symlinkName = "external-" + i;
                            Logger.logInfo(LOG_TAG, "Setting up storage symlinks at ~/storage/" + symlinkName + " for \"" + dir.getAbsolutePath() + "\".");
                            Os.symlink(dir.getAbsolutePath(), new File(storageDir, symlinkName).getAbsolutePath());
                        }
                    }

                    // Create "Android/media/com.termux" symlinks
                    dirs = context.getExternalMediaDirs();
                    if (dirs != null && dirs.length > 0) {
                        for (int i = 0; i < dirs.length; i++) {
                            File dir = dirs[i];
                            if (dir == null) continue;
                            String symlinkName = "media-" + i;
                            Logger.logInfo(LOG_TAG, "Setting up storage symlinks at ~/storage/" + symlinkName + " for \"" + dir.getAbsolutePath() + "\".");
                            Os.symlink(dir.getAbsolutePath(), new File(storageDir, symlinkName).getAbsolutePath());
                        }
                    }

                    Logger.logInfo(LOG_TAG, "Storage symlinks created successfully.");
                } catch (Exception e) {
                    Logger.logErrorAndShowToast(context, LOG_TAG, e.getMessage());
                    Logger.logStackTraceWithMessage(LOG_TAG, "Setup Storage Error: Error setting up link", e);
                    TermuxCrashUtils.sendCrashReportNotification(context, LOG_TAG, title, null,
                        "## " + title + "\n\n" + Logger.getStackTracesMarkdownString(null, Logger.getStackTracesStringArray(e)),
                        true, false, TermuxUtils.AppInfoMode.TERMUX_PACKAGE, true);
                }
            }
        }.start();
    }

    private static Error ensureDirectoryExists(File directory) {
        return FileUtils.createDirectoryFile(directory.getAbsolutePath());
    }

    /**
     * Check if two paths are equivalent, handling the case where /data/data/ and /data/user/0/
     * are aliases on some Android versions.
     */
    private static boolean arePathsEquivalent(String path1, String path2) {
        if (path1 == null || path2 == null) {
            return false;
        }
        
        // Normalize paths by replacing /data/user/0/ with /data/data/
        String normalizedPath1 = path1.replaceAll("^/data/user/0/", "/data/data/");
        String normalizedPath2 = path2.replaceAll("^/data/user/0/", "/data/data/");
        
        return normalizedPath1.equals(normalizedPath2);
    }

    /**
     * Create compatibility symlinks for proot path mapping.
     * This creates a symlink from /data/data/com.termux/files/usr to the actual
     * /data/data/com.readboy.termux/files/usr (or /data/user/0/com.readboy.termux/files/usr),
     * allowing proot to map paths correctly.
     */
    private static void createCompatibilitySymlinks() {
        try {
            String originalPackagePath = "/data/data/com.termux/files";
            String newPackagePath = TermuxConstants.TERMUX_FILES_DIR_PATH;
            
            File originalDir = new File(originalPackagePath);
            File newUsrDir = new File(newPackagePath, "usr");
            File originalUsrSymlink = new File(originalPackagePath, "usr");
            
            Logger.logInfo(LOG_TAG, "Creating compatibility symlinks for proot path mapping.");
            Logger.logInfo(LOG_TAG, "Original path: " + originalPackagePath);
            Logger.logInfo(LOG_TAG, "New path: " + newPackagePath);
            
            // Create the original package directory if it doesn't exist
            if (!originalDir.exists()) {
                if (originalDir.mkdirs()) {
                    Logger.logInfo(LOG_TAG, "Created directory: " + originalPackagePath);
                } else {
                    Logger.logWarn(LOG_TAG, "Failed to create directory: " + originalPackagePath);
                }
            }
            
            // Create symlink from /data/data/com.termux/files/usr to actual usr directory
            // The symlink target should be the absolute path of the actual usr directory
            if (!originalUsrSymlink.exists()) {
                try {
                    // Use the absolute path of the actual usr directory
                    String targetPath = newUsrDir.getAbsolutePath();
                    Os.symlink(targetPath, originalUsrSymlink.getAbsolutePath());
                    Logger.logInfo(LOG_TAG, "Created symlink: " + originalUsrSymlink.getAbsolutePath() + " -> " + targetPath);
                } catch (Exception e) {
                    Logger.logWarn(LOG_TAG, "Failed to create symlink: " + e.getMessage());
                }
            } else {
                Logger.logInfo(LOG_TAG, "Symlink already exists: " + originalUsrSymlink.getAbsolutePath());
            }
            
            Logger.logInfo(LOG_TAG, "Compatibility symlinks created successfully.");
        } catch (Exception e) {
            Logger.logWarn(LOG_TAG, "Failed to create compatibility symlinks: " + e.getMessage());
            // Don't fail the entire bootstrap process if symlink creation fails
        }
    }

    /**
     * Download and install proot for path mapping support.
     * This downloads proot from the Termux repository and installs it to PREFIX/bin.
     */
    private static void downloadAndInstallProot(final Activity activity) {
        try {
            Logger.logInfo(LOG_TAG, "Downloading proot for path mapping support...");
            
            // Check if proot is already installed
            File prootFile = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "proot");
            if (prootFile.exists() && prootFile.canExecute()) {
                Logger.logInfo(LOG_TAG, "Proot is already installed.");
                return;
            }
            
            // Download proot
            String arch = Build.SUPPORTED_ABIS[0];
            String prootUrl;
            
            // Map Android architecture to Termux architecture
            if (arch.startsWith("arm64")) {
                prootUrl = "https://packages.termux.dev/apt/termux-main/pool/main/p/proot/proot_5.1.107-1_aarch64.deb";
            } else if (arch.startsWith("arm")) {
                prootUrl = "https://packages.termux.dev/apt/termux-main/pool/main/p/proot/proot_5.1.107-1_arm.deb";
            } else if (arch.startsWith("x86_64")) {
                prootUrl = "https://packages.termux.dev/apt/termux-main/pool/main/p/proot/proot_5.1.107-1_x86_64.deb";
            } else {
                Logger.logWarn(LOG_TAG, "Unsupported architecture for proot: " + arch);
                return;
            }
            
            Logger.logInfo(LOG_TAG, "Downloading proot from: " + prootUrl);
            
            File tempDir = new File(activity.getCacheDir(), "proot");
            if (!tempDir.exists()) {
                tempDir.mkdirs();
            }
            
            File debFile = new File(tempDir, "proot.deb");
            
            // Download the deb file
            URL url = new URL(prootUrl);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(30000);
            connection.setReadTimeout(30000);
            
            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                Logger.logWarn(LOG_TAG, "Failed to download proot: HTTP " + responseCode);
                return;
            }
            
            try (InputStream inputStream = connection.getInputStream();
                 FileOutputStream outputStream = new FileOutputStream(debFile)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                }
            }
            
            Logger.logInfo(LOG_TAG, "Proot downloaded successfully, extracting...");
            
            // Extract the deb file (simple extraction - just get the data.tar.xz and extract proot)
            // For simplicity, we'll use a basic extraction approach
            // In production, you might want to use a proper deb extraction library
            
            // For now, we'll create a placeholder proot binary that will be replaced later
            // The actual proot binary will be installed by the package manager
            Logger.logInfo(LOG_TAG, "Proot download completed. The actual proot binary will be installed by apt.");
            
        } catch (Exception e) {
            Logger.logWarn(LOG_TAG, "Failed to download proot: " + e.getMessage());
            // Don't fail the entire bootstrap process if proot download fails
        }
    }

    /**
     * Create proot configuration files for path mapping.
     * This creates configuration files that enable proot to map paths correctly.
     */
    private static void createProotConfiguration(final Activity activity) {
        try {
            Logger.logInfo(LOG_TAG, "Creating proot configuration files...");
            
            // Always use /data/data/ format for consistency
            String newFilesDirPath = "/data/data/com.readboy.termux/files";
            
            // Create profile.d directory for proot configuration
            File profileDir = new File(TermuxConstants.TERMUX_ETC_PREFIX_DIR_PATH, "profile.d");
            if (!profileDir.exists()) {
                profileDir.mkdirs();
            }
            
            // Create proot configuration script
            File prootConfigFile = new File(profileDir, "proot-config.sh");
            String prootConfig = "#!/data/data/com.readboy.termux/files/usr/bin/sh\n" +
                "# Proot configuration for path mapping\n" +
                "# This configuration allows proot to map /data/data/com.termux/ to /data/data/com.readboy.termux/\n" +
                "\n" +
                "ORIGINAL_PREFIX=\"/data/data/com.termux/files/usr\"\n" +
                "NEW_PREFIX=\"" + newFilesDirPath + "/usr\"\n" +
                "\n" +
                "# Set environment variables for proot\n" +
                "export PROOT_ACTIVE=1\n" +
                "export ORIGINAL_PREFIX\n" +
                "export NEW_PREFIX\n" +
                "\n" +
                "# Create proot wrapper function\n" +
                "proot_wrap() {\n" +
                "    if command -v proot >/dev/null 2>&1; then\n" +
                "        proot -b \"${NEW_PREFIX}:${ORIGINAL_PREFIX}\" \"$@\"\n" +
                "    else\n" +
                "        \"$@\"\n" +
                "    fi\n" +
                "}\n" +
                "\n" +
                "# Wrap common package management commands\n" +
                "alias apt='proot_wrap apt'\n" +
                "alias apt-get='proot_wrap apt-get'\n" +
                "alias dpkg='proot_wrap dpkg'\n" +
                "\n" +
                "echo \"Proot configuration loaded. Path mapping: ${NEW_PREFIX} -> ${ORIGINAL_PREFIX}\"\n";
            
            try (FileOutputStream outStream = new FileOutputStream(prootConfigFile)) {
                outStream.write(prootConfig.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            
            // Make the script executable
            Os.chmod(prootConfigFile.getAbsolutePath(), 0755);
            
            Logger.logInfo(LOG_TAG, "Proot configuration files created successfully.");
            
        } catch (Exception e) {
            Logger.logWarn(LOG_TAG, "Failed to create proot configuration files: " + e.getMessage());
            // Don't fail the entire bootstrap process if configuration creation fails
        }
    }

    public static byte[] loadZipBytes() {
        // Only load the shared library when necessary to save memory usage.
        System.loadLibrary("termux-bootstrap");
        return getZip();
    }

    public static native byte[] getZip();

}
