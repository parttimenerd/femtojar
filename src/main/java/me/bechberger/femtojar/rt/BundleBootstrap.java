package me.bechberger.femtojar.rt;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLStreamHandlerFactory;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.zip.InflaterInputStream;

/**
 * Bootstrap class that decompresses the bundled class blob and redirects execution
 * to the original Main-Class using a nested custom ClassLoader.
 * <p>
 * This becomes the Main-Class in the manifest.
 * <p>
 * Bytecode-size-conscious: avoids lambdas, string concat via {@code +}, and
 * unnecessary method/field overhead to keep the runtime footprint small.
 */
public class BundleBootstrap extends ClassLoader implements URLStreamHandlerFactory {

    private Map<String, int[]> classIndex;
    private Map<String, int[]> resourceIndex;
    private byte[] classData;
    private boolean bundledResourcesEnabled;
    private Set<String> directoryPaths;
    private URL jarUrl;
    private Manifest manifest;
    private ClassLoader parentCL;

    public static void main(String[] args) throws Exception {
        new BundleBootstrap().start(args);
    }

    private void start(String[] args) throws Exception {
        ClassLoader cl = BundleBootstrap.class.getClassLoader();
        parentCL = cl != null ? cl : ClassLoader.getSystemClassLoader();

        readPackedBlob();
        readManifest();
        computeDirectoryPaths();
        resolveJarUrl();

        Thread.currentThread().setContextClassLoader(this);
        if (bundledResourcesEnabled) {
            installFemtojarUrlHandlerFactory();
        }

        String originalMainClass = manifest.getMainAttributes().getValue("X-Original-Main-Class");
        if (originalMainClass == null) {
            throw new IOException("Missing X-Original-Main-Class");
        }

        Class<?> mainClass = Class.forName(originalMainClass, true, this);
        Method mainMethod = mainClass.getMethod("main", String[].class);
        mainMethod.invoke(null, (Object) args);
    }

    private void resolveJarUrl() {
        try {
            URL blobUrl = parentCL.getResource("__classes.zlib");
            if (blobUrl != null && "jar".equals(blobUrl.getProtocol())) {
                String spec = blobUrl.toString();
                int bangIdx = spec.indexOf("!/");
                if (bangIdx > 0) {
                    jarUrl = new URL(spec.substring(4, bangIdx)); // skip "jar:"
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void computeDirectoryPaths() {
        directoryPaths = new HashSet<>();
        for (String name : classIndex.keySet()) {
            // class names use dots, convert to path
            addParentDirectories(name.replace('.', '/'));
        }
        for (String name : resourceIndex.keySet()) {
            addParentDirectories(name);
        }
    }

    private void addParentDirectories(String path) {
        int idx = path.lastIndexOf('/');
        while (idx > 0) {
            String dir = path.substring(0, idx);
            if (!directoryPaths.add(dir)) {
                break; // already added this and all parents
            }
            idx = dir.lastIndexOf('/');
        }
    }

    private void installFemtojarUrlHandlerFactory() {
        try {
            URL.setURLStreamHandlerFactory(this);
        } catch (Error e) {
            System.err.println("[femtojar] URL handler factory already set; femtojar: URLs from string form will fail.");
        }
    }

    @Override
    public java.net.URLStreamHandler createURLStreamHandler(String protocol) {
        if ("femtojar".equals(protocol)) {
            return new FemtoJarURLStreamHandler(this);
        }
        return null;
    }

    private void readPackedBlob() throws IOException {
        classIndex = new HashMap<>();
        resourceIndex = new HashMap<>();

        byte[] packed = decompressBlobFully();
        try (DataInputStream dis = new DataInputStream(new ByteArrayInputStream(packed))) {
            int config = dis.readUnsignedByte();
            bundledResourcesEnabled = (config & 1) != 0;

            int indexSize = dis.readInt();
            int classBlobOffset = 5 + indexSize;
            if (indexSize < 0 || classBlobOffset < 5 || classBlobOffset > packed.length) {
                throw new IOException("Bad header");
            }

            int version = dis.readInt();
            if (version != 1) {
                throw new IOException("Bad version");
            }

            int numEntries = dis.readInt();
            for (int i = 0; i < numEntries; i++) {
                String className = dis.readUTF();
                int offset = dis.readInt();
                int length = dis.readInt();
                classIndex.put(className, new int[]{offset, length});
            }

            int numResources = dis.readInt();
            for (int i = 0; i < numResources; i++) {
                String resourceName = dis.readUTF();
                int offset = dis.readInt();
                int length = dis.readInt();
                resourceIndex.put(resourceName, new int[]{offset, length});
            }
            int totalUncompressedSize = dis.readInt();

            int classBlobLength = packed.length - classBlobOffset;
            if (classBlobLength != totalUncompressedSize) {
                throw new IOException("Length mismatch");
            }
            classData = new byte[classBlobLength];
            System.arraycopy(packed, classBlobOffset, classData, 0, classBlobLength);
        }
    }

    private byte[] decompressBlobFully() throws IOException {
        InputStream is = parentCL.getResourceAsStream("__classes.zlib");
        if (is == null) {
            throw new IOException("No blob");
        }
        try (InflaterInputStream iis = new InflaterInputStream(is)) {
            return iis.readAllBytes();
        }
    }

    private void readManifest() throws IOException {
        try (InputStream is = parentCL.getResourceAsStream("META-INF/MANIFEST.MF")) {
            if (is == null) {
                throw new IOException("No manifest");
            }
            manifest = new Manifest(is);
        }
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        int[] entry = classIndex.get(name);
        if (entry == null) {
            throw new ClassNotFoundException(name);
        }

        int lastDot = name.lastIndexOf('.');
        if (lastDot > 0) {
            String packageName = name.substring(0, lastDot);
            if (getDefinedPackage(packageName) == null) {
                try {
                    String specTitle = null, specVersion = null, specVendor = null;
                    String implTitle = null, implVersion = null, implVendor = null;
                    if (manifest != null) {
                        String pkgPath = packageName.replace('.', '/').concat("/");
                        Attributes pkgAttrs = manifest.getAttributes(pkgPath);
                        Attributes mainAttrs = manifest.getMainAttributes();
                        specTitle = manifestAttr(pkgAttrs, mainAttrs, "Specification-Title");
                        specVersion = manifestAttr(pkgAttrs, mainAttrs, "Specification-Version");
                        specVendor = manifestAttr(pkgAttrs, mainAttrs, "Specification-Vendor");
                        implTitle = manifestAttr(pkgAttrs, mainAttrs, "Implementation-Title");
                        implVersion = manifestAttr(pkgAttrs, mainAttrs, "Implementation-Version");
                        implVendor = manifestAttr(pkgAttrs, mainAttrs, "Implementation-Vendor");
                    }
                    definePackage(packageName, specTitle, specVersion, specVendor,
                            implTitle, implVersion, implVendor, null);
                } catch (IllegalArgumentException ignored) {
                }
            }
        }

        ProtectionDomain pd = null;
        if (jarUrl != null) {
            CodeSource cs = new CodeSource(jarUrl, (Certificate[]) null);
            pd = new ProtectionDomain(cs, null, this, null);
        }

        return defineClass(name, classData, entry[0], entry[1], pd);
    }

    private static String manifestAttr(Attributes pkgAttrs, Attributes mainAttrs, String name) {
        if (pkgAttrs != null) {
            String val = pkgAttrs.getValue(name);
            if (val != null) return val;
        }
        return mainAttrs != null ? mainAttrs.getValue(name) : null;
    }

    @Override
    public InputStream getResourceAsStream(String name) {
        int[] entry = resourceIndex.get(name);
        if (entry != null) {
            return new ByteArrayInputStream(classData, entry[0], entry[1]);
        }
        // Also serve .class entries from classIndex (BUG-17)
        int[] classEntry = resolveClassEntry(name);
        if (classEntry != null) {
            return new ByteArrayInputStream(classData, classEntry[0], classEntry[1]);
        }
        return super.getResourceAsStream(name);
    }

    private int[] resolveClassEntry(String resourceName) {
        if (resourceName != null && resourceName.endsWith(".class")) {
            String className = resourceName.substring(0, resourceName.length() - 6).replace('/', '.');
            return classIndex.get(className);
        }
        return null;
    }

    @Override
    protected URL findResource(String name) {
        // Check bundled resources
        if (resourceIndex.containsKey(name)) {
            return makeFemtojarUrl(name);
        }
        // Check class entries as resources (BUG-6)
        if (resolveClassEntry(name) != null) {
            return makeFemtojarUrl(name);
        }
        // Synthesize directory resources from known paths (BUG-28)
        String dirName = name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
        if (directoryPaths != null && directoryPaths.contains(dirName)) {
            return makeFemtojarUrl(name);
        }
        return null;
    }

    private URL makeFemtojarUrl(String name) {
        try {
            return new URL(null, "femtojar:/".concat(name), new FemtoJarURLStreamHandler(this));
        } catch (IOException ignored) {
            return null;
        }
    }

    @Override
    protected Enumeration<URL> findResources(String name) throws IOException {
        URL localResource = findResource(name);
        Enumeration<URL> parentResources = super.findResources(name);
        if (localResource == null) {
            return parentResources;
        }
        // Merge local + parent results
        List<URL> merged = new ArrayList<>();
        merged.add(localResource);
        while (parentResources.hasMoreElements()) {
            merged.add(parentResources.nextElement());
        }
        return java.util.Collections.enumeration(merged);
    }

    static class FemtoJarURLStreamHandler extends java.net.URLStreamHandler {
        private final BundleBootstrap bootstrap;

        FemtoJarURLStreamHandler(BundleBootstrap bootstrap) {
            this.bootstrap = bootstrap;
        }

        @Override
        protected java.net.URLConnection openConnection(URL url) throws IOException {
            String path = url.getPath();
            String resourceName = path.startsWith("/") ? path.substring(1) : path;
            int[] entry = bootstrap.resourceIndex.get(resourceName);
            if (entry != null) {
                return new FemtoJarURLConnection(url, bootstrap.classData, entry[0], entry[1]);
            }
            int[] classEntry = bootstrap.resolveClassEntry(resourceName);
            if (classEntry != null) {
                return new FemtoJarURLConnection(url, bootstrap.classData, classEntry[0], classEntry[1]);
            }
            String dirName = resourceName.endsWith("/") ? resourceName.substring(0, resourceName.length() - 1) : resourceName;
            if (bootstrap.directoryPaths != null && bootstrap.directoryPaths.contains(dirName)) {
                return new FemtoJarURLConnection(url, new byte[0], 0, 0);
            }
            throw new IOException("Not found: ".concat(resourceName));
        }
    }

    static class FemtoJarURLConnection extends java.net.URLConnection {
        private final byte[] data;
        private final int offset;
        private final int length;

        FemtoJarURLConnection(URL url, byte[] data, int offset, int length) {
            super(url);
            this.data = data;
            this.offset = offset;
            this.length = length;
        }

        @Override
        public void connect() {}

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(data, offset, length);
        }

        @Override
        public int getContentLength() {
            return length;
        }
    }
}