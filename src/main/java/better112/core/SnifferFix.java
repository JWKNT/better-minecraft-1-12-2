package better112.core;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.security.CodeSigner;
import java.security.CodeSource;
import java.security.SecureClassLoader;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import net.minecraft.launchwrapper.Launch;
import net.minecraft.launchwrapper.LaunchClassLoader;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * TickCentral (LagGoggles' tick hook) asks, for each class the game loads, whether it is a block, a ticking
 * thing or an entity, and for that reads every superclass in full, all its methods' code included, only to see
 * whether the class carries a Mixin annotation: seconds of each game start and server start. Its checker class
 * is kept from the class patchers (patching it would call itself), so this defines it before anything loads it,
 * with that one read told to skip method code: class annotations are read the same either way.
 */
final class SnifferFix {

    static final String ID = "tickcentral_sniffer";
    private static final String NAME = "com.github.terminatornl.tickcentral.api.ClassSniffer";
    private static volatile boolean done;

    private SnifferFix() {}

    /** Called when the plugin is made and again when it gets its data; does its work at most once. */
    static synchronized void apply() {
        if (done) {
            return;
        }
        try {
            LaunchClassLoader loader = Launch.classLoader;
            if (loader == null) {
                return;
            }
            if (!PatchState.enabled(ID)) {
                done = true;
                return;
            }
            Method findLoaded = ClassLoader.class.getDeclaredMethod("findLoadedClass", String.class);
            findLoaded.setAccessible(true);
            Field cachedField = LaunchClassLoader.class.getDeclaredField("cachedClasses");
            cachedField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, Class<?>> cached = (Map<String, Class<?>>) cachedField.get(loader);
            if (findLoaded.invoke(loader, NAME) != null || cached.containsKey(NAME)) {
                done = true; // too late: something has it already
                PatchState.failed(ID);
                return;
            }
            String path = NAME.replace('.', '/') + ".class";
            URL url = loader.findResource(path);
            if (url == null) {
                return; // TickCentral not there, or its jar not added yet: try again later
            }
            done = true;
            URLConnection connection = url.openConnection();
            if (!(connection instanceof JarURLConnection)) {
                PatchState.failed(ID);
                return;
            }
            JarURLConnection jarConnection = (JarURLConnection) connection;
            JarFile jar = jarConnection.getJarFile();
            JarEntry entry = jar.getJarEntry(path);
            if (entry == null) {
                PatchState.failed(ID);
                return;
            }
            byte[] bytes;
            InputStream in = jar.getInputStream(entry);
            try {
                bytes = readFully(in);
            } finally {
                in.close();
            }
            // The signers are known only once the entry has been read; the class keeps them, as its package's others do.
            CodeSigner[] signers = entry.getCodeSigners();
            byte[] patched = patch(bytes);
            if (patched == null) {
                PatchState.failed(ID);
                return;
            }
            Method define = SecureClassLoader.class.getDeclaredMethod("defineClass", String.class, byte[].class, int.class, int.class,
                    CodeSource.class);
            define.setAccessible(true);
            Class<?> made = (Class<?>) define.invoke(loader, NAME, patched, 0, patched.length,
                    new CodeSource(jarConnection.getJarFileURL(), signers));
            cached.put(NAME, made);
            PatchState.applied(ID);
        } catch (Throwable t) {
            done = true;
            PatchState.failed(ID);
            System.err.println("[Better112] Left TickCentral's class check as it is: " + t);
        }
    }

    /** The class with its superclass read told to skip method code, or null if it is not the code expected. */
    static byte[] patch(byte[] bytes) {
        ClassNode c = new ClassNode();
        new ClassReader(bytes).accept(c, 0);
        MethodNode m = null;
        for (MethodNode each : c.methods) {
            if (each.name.equals("isInstanceOf") && each.desc.equals("(Lorg/objectweb/asm/ClassReader;Ljava/lang/String;Z)Z")) {
                m = each;
            }
        }
        if (m == null) {
            return null;
        }
        InsnNode flags = null;
        int nodeVar = -1;
        for (AbstractInsnNode n = m.instructions.getFirst(); n != null; n = n.getNext()) {
            if (n instanceof MethodInsnNode && n.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && ((MethodInsnNode) n).owner.equals("org/objectweb/asm/ClassReader") && ((MethodInsnNode) n).name.equals("accept")
                    && ((MethodInsnNode) n).desc.equals("(Lorg/objectweb/asm/ClassVisitor;I)V")) {
                AbstractInsnNode flag = n.getPrevious();
                AbstractInsnNode visitor = flag == null ? null : flag.getPrevious();
                if (flags != null || flag == null || flag.getOpcode() != Opcodes.ICONST_0 || !(visitor instanceof VarInsnNode)
                        || visitor.getOpcode() != Opcodes.ALOAD) {
                    return null;
                }
                flags = (InsnNode) flag;
                nodeVar = ((VarInsnNode) visitor).var;
            }
        }
        if (flags == null) {
            return null;
        }
        // The class read is used for nothing but its class annotations.
        for (AbstractInsnNode n = m.instructions.getFirst(); n != null; n = n.getNext()) {
            if (n instanceof VarInsnNode && ((VarInsnNode) n).var == nodeVar && n.getOpcode() == Opcodes.ALOAD && n != flags.getPrevious()) {
                AbstractInsnNode next = n.getNext();
                if (!(next instanceof FieldInsnNode) || !((FieldInsnNode) next).owner.equals("org/objectweb/asm/tree/ClassNode")
                        || !((FieldInsnNode) next).name.equals("invisibleAnnotations")) {
                    return null;
                }
            }
        }
        m.instructions.set(flags, new InsnNode(Opcodes.ICONST_1)); // ClassReader.SKIP_CODE
        ClassWriter w = new ClassWriter(0);
        c.accept(w);
        return w.toByteArray();
    }

    private static byte[] readFully(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(16384);
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) > 0) {
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }
}
