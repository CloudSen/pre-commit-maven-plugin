package no.oms.maven.precommit.lib;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.AccessDeniedException;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

class ArchiveExtractionException extends Exception {
    ArchiveExtractionException(String message) {
        super(message);
    }

    ArchiveExtractionException(String message, Throwable cause) {
        super(message, cause);
    }
}

interface ArchiveExtractor {
    void extract(String archive, String destinationDirectory) throws ArchiveExtractionException;
}

final class DefaultArchiveExtractor implements ArchiveExtractor {
    private static final Logger LOG = LoggerFactory.getLogger(DefaultArchiveExtractor.class);

    private void prepDestination(File path, boolean directory) throws IOException {
        File parentDir = path.getParentFile();
        if (!parentDir.exists() && !parentDir.mkdirs()) {
            throw new IOException("Failed to create directory: " + parentDir.getAbsolutePath());
        }
        if (!directory && !parentDir.canWrite()) {
            throw new AccessDeniedException(String.format("Could not get write permissions for '%s'", parentDir.getAbsolutePath()));
        }
    }

    @Override
    public void extract(String archive, String destinationDirectory) throws ArchiveExtractionException {
        File archiveFile = new File(archive);
        File destinationDir = new File(destinationDirectory);

        try (FileInputStream fis = new FileInputStream(archiveFile)) {
            String extension = getExtension(archiveFile.getName());

            if ("zip".equalsIgnoreCase(extension)) {
                extractZip(fis, destinationDir);
            } else if ("gz".equalsIgnoreCase(extension) || "tgz".equalsIgnoreCase(extension)) {
                extractTarGz(fis, destinationDir);
            } else {
                throw new ArchiveExtractionException("Unsupported archive format for: " + archive);
            }
        } catch (IOException e) {
            throw new ArchiveExtractionException("Could not extract archive: '" + archive + "'", e);
        }
    }

    private void extractZip(InputStream fis, File destinationDirectory) throws IOException {
        // Create a temporary file to store the zip contents
        File tempZipFile = File.createTempFile("tempzip", ".zip");
        tempZipFile.deleteOnExit();

        try {
            // Write the InputStream to the temporary file
            try (OutputStream fos = new FileOutputStream(tempZipFile)) {
                IOUtils.copy(fis, fos);
            }

            // Use the ZipFile constructor with the temporary file
            try (ZipFile zipFile = new ZipFile(tempZipFile)) {
                Enumeration<? extends ZipEntry> entries = zipFile.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    File destPath = new File(destinationDirectory, entry.getName());
                    LOG.debug("Extracting {} to {}", entry.getName(), destPath.getAbsolutePath());
                    prepDestination(destPath, entry.isDirectory());
                    if (!entry.isDirectory()) {
                        try (InputStream in = zipFile.getInputStream(entry);
                             OutputStream out = new FileOutputStream(destPath)) {
                            LOG.debug("Writing file: {}", destPath.getAbsolutePath());
                            IOUtils.copy(in, out);
                        }
                    }
                }
            }
        } finally {
            // Ensure the temporary file is deleted
            if (!tempZipFile.delete()) {
                LOG.warn("Failed to delete temporary file: {}", tempZipFile.getAbsolutePath());
            }
        }
    }

    private void extractTarGz(InputStream fis, File destinationDirectory) throws IOException {
        try (TarArchiveInputStream tarIn = new TarArchiveInputStream(new GzipCompressorInputStream(fis))) {
            TarArchiveEntry tarEntry;
            while ((tarEntry = tarIn.getNextTarEntry()) != null) {
                String name = strip(tarEntry.getName());
                File destPath = new File(destinationDirectory, name);
                LOG.debug("Extracting {} to {}", name, destPath.getAbsolutePath());
                prepDestination(destPath, tarEntry.isDirectory());

                // Check if the entry is outside the destination directory
                if (!destPath.getCanonicalPath().startsWith(destinationDirectory.getCanonicalPath())) {
                    throw new IOException("Expanding " + name + " would create file outside of " + destinationDirectory);
                }

                if (!tarEntry.isDirectory()) {
                    destPath.createNewFile();
                    boolean isExecutable = (tarEntry.getMode() & 0100) > 0;
                    destPath.setExecutable(isExecutable);
                    LOG.debug("Writing file: {}, executable: {}", destPath.getAbsolutePath(), isExecutable);

                    try (OutputStream out = new FileOutputStream(destPath)) {
                        IOUtils.copy(tarIn, out);
                    }
                }
            }
        }
    }

    private String strip(String input) {
        return input.replaceFirst("^[-\\w.]+/", "");
    }

    private String getExtension(String filename) {
        String lowerFilename = filename.toLowerCase();
        int lastDotIndex = lowerFilename.lastIndexOf('.');
        if (lastDotIndex == -1) {
            return "";
        }
        String extension = lowerFilename.substring(lastDotIndex + 1);
        if (extension.equals("gz")) {
            int secondLastDotIndex = lowerFilename.lastIndexOf('.', lastDotIndex - 1);
            if (secondLastDotIndex != -1) {
                String doubleExtension = lowerFilename.substring(secondLastDotIndex + 1);
                if (doubleExtension.equals("tar")) {
                    return "tar.gz";
                }
            }
        }
        LOG.info("Extracted extension: {}", extension);
        return extension;
    }
}