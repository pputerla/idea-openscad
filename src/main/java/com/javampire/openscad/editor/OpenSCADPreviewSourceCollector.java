package com.javampire.openscad.editor;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.OrderRootType;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.roots.libraries.Library;
import com.intellij.openapi.roots.libraries.LibraryTablesRegistrar;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.javampire.openscad.psi.OpenSCADImportUtil;
import com.javampire.openscad.settings.OpenSCADInfo;
import com.javampire.openscad.settings.OpenSCADSettings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Collects OpenSCAD source files for in-browser WASM preview, including transitive imports.
 * <p>
 * Uses text scanning for {@code use}/{@code include} paths so preview bundling matches OpenSCAD's
 * import graph even when PSI resolution is incomplete. Falls back to VFS lookup under the content root
 * and OpenSCAD library paths.
 */
public final class OpenSCADPreviewSourceCollector {

    private static final Logger LOG = Logger.getInstance(OpenSCADPreviewSourceCollector.class);
    private static final String WORK_ROOT = "/work";
    static final long MAX_PREVIEW_ASSET_BYTES = 128L * 1024L * 1024L;
    private static final String PREVIEW_ASSET_LIMIT_MESSAGE =
            "Preview files in this directory exceed the 128 MiB limit. Remove some files and refresh the preview.";
    private static final Pattern IMPORT_PATTERN = Pattern.compile("\\b(?:use|include)\\s*<([^>]+)>");
    private static final Pattern TEXTMETRICS_PATTERN = Pattern.compile("\\b(?:textmetrics|fontmetrics)\\s*\\(");
    private static final Pattern PREVIEW_FONTS_PATTERN = Pattern.compile(
            "\\b(?:textmetrics|fontmetrics)\\s*\\(|\\btext\\s*\\("
    );

    private OpenSCADPreviewSourceCollector() {
    }

    public record PreviewSources(@NotNull String mainPath, @NotNull Map<String, String> files, @NotNull Map<String, byte[]> assets,
            boolean loadPreviewFonts, boolean enableTextMetrics, @NotNull Map<String, byte[]> userFonts,
            @Nullable String errorMessage) {}

    private record PendingFile(@NotNull String virtualPath, @Nullable PsiFile psiFile, @Nullable VirtualFile virtualFile) {}

    @Nullable
    public static PreviewSources collect(@NotNull final Project project, @NotNull final VirtualFile scadFile) {
        final PsiFile mainPsiFile = PsiManager.getInstance(project).findFile(scadFile);
        if (mainPsiFile == null) {
            return null;
        }

        final ProjectFileIndex fileIndex = ProjectRootManager.getInstance(project).getFileIndex();
        final VirtualFile contentRoot = fileIndex.getContentRootForFile(scadFile);
        if (contentRoot == null) {
            return null;
        }

        final String mainRelativePath = VfsUtil.getRelativePath(scadFile, contentRoot, '/');
        if (mainRelativePath == null) {
            return null;
        }

        final String mainPath = toVirtualPath(mainRelativePath);
        final Map<String, String> files = new LinkedHashMap<>();
        final Set<String> visitedPaths = new java.util.HashSet<>();
        final Queue<PendingFile> queue = new ArrayDeque<>();
        final List<String> libraryRoots = collectLibraryRoots();
        queue.add(new PendingFile(mainPath, mainPsiFile, scadFile));

        while (!queue.isEmpty()) {
            final PendingFile pending = queue.remove();
            if (visitedPaths.contains(pending.virtualPath())) {
                continue;
            }

            final String content = readContent(pending.psiFile(), pending.virtualFile());
            if (content == null) {
                LOG.warn("OpenSCAD preview: could not read " + pending.virtualPath());
                continue;
            }
            visitedPaths.add(pending.virtualPath());
            files.put(pending.virtualPath(), content);

            final String projectRelativePath = toProjectRelativePath(pending.virtualPath());
            for (final String importPath : collectImportPathsFromText(content)) {
                final String importVirtualPath = resolveImportVirtualPath(pending.virtualPath(), importPath);
                if (visitedPaths.contains(importVirtualPath)) {
                    continue;
                }

                final PendingFile imported = resolveImportedFile(
                        project,
                        contentRoot,
                        libraryRoots,
                        pending.psiFile(),
                        pending.virtualFile(),
                        projectRelativePath,
                        importPath,
                        importVirtualPath
                );
                if (imported != null) {
                    queue.add(imported);
                }
                else {
                    LOG.warn("OpenSCAD preview: unresolved import <" + importPath + "> from " + pending.virtualPath());
                }
            }
        }

        final Map<String, byte[]> assets;
        try {
            final VirtualFile scadDirectory = scadFile.getParent();
            assets = scadDirectory == null || !scadDirectory.isInLocalFileSystem()
                    ? Map.of()
                    : collectAssets(
                            Path.of(scadDirectory.getPath()),
                            parentVirtualPath(mainPath),
                            files
                    );
        }
        catch (PreviewAssetLimitException e) {
            LOG.warn("OpenSCAD preview: " + PREVIEW_ASSET_LIMIT_MESSAGE + " (" + scadFile.getPath() + ")");
            return new PreviewSources(mainPath, Map.of(), Map.of(), false, false, Map.of(), PREVIEW_ASSET_LIMIT_MESSAGE);
        }

        final boolean loadPreviewFonts = requiresPreviewFonts(files);
        final Map<String, byte[]> userFonts = loadPreviewFonts
                ? OpenSCADPreviewFontCollector.collectUserFonts(OpenSCADSettings.getInstance().getPreviewFontDirectories())
                : Map.of();
        return new PreviewSources(mainPath, files, assets, loadPreviewFonts, requiresTextMetrics(files), userFonts, null);
    }

    @NotNull
    static Map<String, byte[]> collectAssets(@NotNull final Path sourceDirectory, @NotNull final String virtualDirectory,
                                              @NotNull final Map<String, String> sourceFiles) {
        final Path snapshotRoot;
        try {
            snapshotRoot = sourceDirectory.toRealPath();
        }
        catch (IOException e) {
            LOG.warn("OpenSCAD preview: could not resolve the source directory " + sourceDirectory, e);
            return Map.of();
        }

        final Map<String, byte[]> assets = new LinkedHashMap<>();
        final long[] totalBytes = {0};

        try {
            Files.walkFileTree(snapshotRoot, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(@NotNull final Path file, @NotNull final BasicFileAttributes attributes) throws IOException {
                    final Path canonicalPath = file.toRealPath();
                    if (!canonicalPath.startsWith(snapshotRoot)) {
                        LOG.warn("OpenSCAD preview: ignoring file outside the source directory: " + file);
                        return FileVisitResult.CONTINUE;
                    }
                    if (!attributes.isRegularFile()) {
                        return FileVisitResult.CONTINUE;
                    }

                    final Path relativePath = snapshotRoot.relativize(file.toAbsolutePath().normalize());
                    if (relativePath.isAbsolute() || relativePath.startsWith("..")) {
                        LOG.warn("OpenSCAD preview: ignoring file with an invalid relative path: " + file);
                        return FileVisitResult.CONTINUE;
                    }
                    final String virtualPath = virtualDirectory + "/" + relativePath.toString().replace(
                            java.io.File.separatorChar,
                            '/'
                    );
                    final long remainingBytes = MAX_PREVIEW_ASSET_BYTES - totalBytes[0];
                    if (sourceFiles.containsKey(virtualPath)) {
                        final long sourceBytes = sourceFiles.get(virtualPath).getBytes(StandardCharsets.UTF_8).length;
                        if (sourceBytes > remainingBytes) {
                            throw new PreviewAssetLimitException();
                        }
                        totalBytes[0] += sourceBytes;
                        return FileVisitResult.CONTINUE;
                    }

                    final byte[] content = readAsset(file, remainingBytes);
                    totalBytes[0] += content.length;
                    assets.put(virtualPath, content);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(@NotNull final Path file, @NotNull final IOException exception) {
                    LOG.warn("OpenSCAD preview: could not snapshot " + file, exception);
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        catch (PreviewAssetLimitException e) {
            throw e;
        }
        catch (IOException e) {
            LOG.warn("OpenSCAD preview: could not snapshot files under " + snapshotRoot, e);
        }
        return assets;
    }

    @NotNull
    private static byte[] readAsset(@NotNull final Path file, final long remainingBytes) throws IOException {
        final long reportedSize = Files.size(file);
        if (reportedSize > remainingBytes) {
            throw new PreviewAssetLimitException();
        }
        final int initialCapacity = (int)Math.min(reportedSize, 8192);
        final ByteArrayOutputStream content = new ByteArrayOutputStream(initialCapacity);
        final byte[] buffer = new byte[8192];
        try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            int bytesRead;
            while ((bytesRead = input.read(buffer)) != -1) {
                if ((long)content.size() + bytesRead > remainingBytes) {
                    throw new PreviewAssetLimitException();
                }
                content.write(buffer, 0, bytesRead);
            }
        }
        return content.toByteArray();
    }

    static final class PreviewAssetLimitException extends RuntimeException {
        private PreviewAssetLimitException() {
            super(PREVIEW_ASSET_LIMIT_MESSAGE);
        }
    }

    @NotNull
    private static String parentVirtualPath(@NotNull final String mainPath) {
        final int separator = mainPath.lastIndexOf('/');
        return separator > WORK_ROOT.length() ? mainPath.substring(0, separator) : WORK_ROOT;
    }

    static boolean requiresPreviewFonts(@NotNull final Map<String, String> files) {
        for (final String content : files.values()) {
            if (requiresPreviewFonts(content)) {
                return true;
            }
        }
        return false;
    }

    static boolean requiresPreviewFonts(@NotNull final String content) {
        return PREVIEW_FONTS_PATTERN.matcher(content).find();
    }

    static boolean requiresTextMetrics(@NotNull final Map<String, String> files) {
        for (final String content : files.values()) {
            if (requiresTextMetrics(content)) {
                return true;
            }
        }
        return false;
    }

    static boolean requiresTextMetrics(@NotNull final String content) {
        return TEXTMETRICS_PATTERN.matcher(content).find();
    }

    @NotNull
    static List<String> collectImportPathsFromText(@NotNull final String content) {
        final List<String> paths = new ArrayList<>();
        final Matcher matcher = IMPORT_PATTERN.matcher(content);
        while (matcher.find()) {
            paths.add(matcher.group(1));
        }
        return paths;
    }

    @Nullable
    private static PendingFile resolveImportedFile(@NotNull final Project project, @NotNull final VirtualFile contentRoot, @NotNull final List<String> libraryRoots, @Nullable final PsiFile importerPsiFile,
                                                   @Nullable final VirtualFile importerVirtualFile, @NotNull final String importerProjectRelativePath, @NotNull final String importPath,
                                                   @NotNull final String importVirtualPath) {
        final VirtualFile importerOnDisk = firstNonNull(importerVirtualFile, psiVirtualFile(importerPsiFile));
        if (importerOnDisk != null && importerOnDisk.getParent() != null) {
            final VirtualFile resolved = importerOnDisk.getParent().findFileByRelativePath(importPath);
            if (resolved != null && !resolved.isDirectory()) {
                final PsiFile psiFile = PsiManager.getInstance(project).findFile(resolved);
                return new PendingFile(importVirtualPath, psiFile, resolved);
            }
        }

        final String importedProjectRelativePath = resolveImportProjectRelativePath(importerProjectRelativePath, importPath);
        final VirtualFile resolvedFromRoot = contentRoot.findFileByRelativePath(importedProjectRelativePath);
        if (resolvedFromRoot != null && !resolvedFromRoot.isDirectory()) {
            final PsiFile psiFile = PsiManager.getInstance(project).findFile(resolvedFromRoot);
            return new PendingFile(importVirtualPath, psiFile, resolvedFromRoot);
        }

        if (usesLibrarySearchPath(importPath)) {
            final VirtualFile resolvedInProject = contentRoot.findFileByRelativePath(importPath);
            if (resolvedInProject != null && !resolvedInProject.isDirectory()) {
                final PsiFile psiFile = PsiManager.getInstance(project).findFile(resolvedInProject);
                return new PendingFile(importVirtualPath, psiFile, resolvedInProject);
            }
            final VirtualFile resolvedFromLibrary = resolveOnLibraryRoots(libraryRoots, importPath);
            if (resolvedFromLibrary != null) {
                final PsiFile psiFile = PsiManager.getInstance(project).findFile(resolvedFromLibrary);
                return new PendingFile(importVirtualPath, psiFile, resolvedFromLibrary);
            }
        }

        if (importerPsiFile != null) {
            for (final PsiFile target : OpenSCADImportUtil.resolveImportFiles(importerPsiFile, importPath)) {
                return new PendingFile(importVirtualPath, target, target.getVirtualFile());
            }
        }

        return null;
    }

    @NotNull
    private static List<String> collectLibraryRoots() {
        final List<String> roots = new ArrayList<>();
        final List<String> fromOpenSCAD = OpenSCADInfo.getLibraryPaths();
        if (fromOpenSCAD != null) {
            roots.addAll(fromOpenSCAD);
        }
        for (final Library library : LibraryTablesRegistrar.getInstance().getLibraryTable().getLibraries()) {
            for (final VirtualFile root : library.getFiles(OrderRootType.CLASSES)) {
                if (root.isInLocalFileSystem()) {
                    roots.add(root.getPath());
                }
            }
        }
        return roots;
    }

    @Nullable
    private static VirtualFile resolveOnLibraryRoots(@NotNull final List<String> libraryRoots,
                                                     @NotNull final String importPath) {
        for (final String rootPath : libraryRoots) {
            final VirtualFile root = LocalFileSystem.getInstance().findFileByPath(rootPath);
            if (root == null) {
                continue;
            }
            final VirtualFile resolved = root.findFileByRelativePath(importPath);
            if (resolved != null && !resolved.isDirectory()) {
                return resolved;
            }
        }
        return null;
    }

    @Nullable
    private static VirtualFile psiVirtualFile(@Nullable final PsiFile psiFile) {
        return psiFile != null ? psiFile.getVirtualFile() : null;
    }

    @Nullable
    private static VirtualFile firstNonNull(@Nullable final VirtualFile first, @Nullable final VirtualFile second) {
        return first != null ? first : second;
    }

    /**
     * Import paths resolved via OpenSCAD library directories (e.g. {@code BOSL2/std.scad}, {@code MCAD/util.scad}).
     */
    static boolean usesLibrarySearchPath(@NotNull final String importPath) {
        if (importPath.startsWith("BOSL/") || importPath.startsWith("BOSL2/")) {
            return true;
        }
        if (importPath.startsWith(".") || importPath.contains("..")) {
            return false;
        }
        if (!importPath.contains("/")) {
            return false;
        }
        return Character.isUpperCase(importPath.charAt(0));
    }

    @Nullable
    static String readContent(@Nullable final PsiFile psiFile, @Nullable final VirtualFile virtualFile) {
        if (psiFile != null) {
            return getFileContent(psiFile);
        }
        if (virtualFile != null) {
            final Document document = FileDocumentManager.getInstance().getDocument(virtualFile);
            if (document != null) {
                return document.getText();
            }
            try {
                return VfsUtil.loadText(virtualFile);
            }
            catch (IOException e) {
                LOG.warn("Failed to read " + virtualFile.getPath(), e);
            }
        }
        return null;
    }

    @NotNull
    static String getFileContent(@NotNull final PsiFile psiFile) {
        final VirtualFile virtualFile = psiFile.getVirtualFile();
        if (virtualFile != null) {
            final Document document = FileDocumentManager.getInstance().getDocument(virtualFile);
            if (document != null) {
                return document.getText();
            }
            try {
                return VfsUtil.loadText(virtualFile);
            }
            catch (IOException e) {
                return psiFile.getText();
            }
        }
        return psiFile.getText();
    }

    @NotNull
    private static String toVirtualPath(@NotNull final String relativePath) {
        return WORK_ROOT + "/" + relativePath;
    }

    @NotNull
    private static String toProjectRelativePath(@NotNull final String virtualPath) {
        if (virtualPath.startsWith(WORK_ROOT + "/")) {
            return virtualPath.substring(WORK_ROOT.length() + 1);
        }
        return virtualPath.startsWith("/") ? virtualPath.substring(1) : virtualPath;
    }

    @NotNull
    static String resolveImportProjectRelativePath(@NotNull final String importerProjectRelativePath,
                                                   @NotNull final String importPath) {
        if (importPath.startsWith("BOSL/")) {
            return "lib/bosl/" + importPath.substring("BOSL/".length());
        }
        if (importPath.startsWith("BOSL2/")) {
            return "lib/bosl2/" + importPath.substring("BOSL2/".length());
        }
        final int slash = importerProjectRelativePath.lastIndexOf('/');
        final String importerDir = slash >= 0 ? importerProjectRelativePath.substring(0, slash) : "";
        return normalizeProjectRelativePath(importerDir + "/" + importPath);
    }

    @NotNull
    static String resolveImportVirtualPath(@NotNull final String importerVirtualPath, @NotNull final String importPath) {
        if (usesLibrarySearchPath(importPath)) {
            return normalizeVirtualPath(WORK_ROOT + "/" + importPath);
        }
        final int slash = importerVirtualPath.lastIndexOf('/');
        final String importerDir = slash >= 0 ? importerVirtualPath.substring(0, slash) : WORK_ROOT;
        return normalizeVirtualPath(importerDir + "/" + importPath);
    }

    @NotNull
    private static String normalizeProjectRelativePath(@NotNull final String path) {
        return normalizeVirtualPath("/" + path).substring(1);
    }

    @NotNull
    private static String normalizeVirtualPath(@NotNull final String path) {
        final String[] parts = path.split("/");
        final java.util.Deque<String> stack = new ArrayDeque<>();
        for (final String part : parts) {
            if (part.isEmpty() || ".".equals(part)) {
                continue;
            }
            if ("..".equals(part)) {
                if (!stack.isEmpty()) {
                    stack.removeLast();
                }
                continue;
            }
            stack.addLast(part);
        }
        final StringBuilder normalized = new StringBuilder();
        for (final String part : stack) {
            normalized.append('/').append(part);
        }
        return normalized.isEmpty() ? WORK_ROOT : normalized.toString();
    }
}
