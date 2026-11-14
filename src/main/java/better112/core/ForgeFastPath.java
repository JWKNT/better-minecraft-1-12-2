package better112.core;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.List;

import net.minecraft.launchwrapper.IClassTransformer;
import net.minecraft.launchwrapper.Launch;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Four of Forge's own class transformers read every class the game loads (about 50,000 in Divine Journey 2), and three
 * of them write it out again, to act on the few classes that have what they look for:
 * <ul>
 * <li>TerminalTransformer reroutes calls to System.exit, Runtime.exit and Runtime.halt, and gives back the very same
 * bytes when there are none;</li>
 * <li>EventSubscriberTransformer makes methods marked @SubscribeEvent public, and gives back the same bytes when there
 * are none;</li>
 * <li>EventSubscriptionTransformer adds a listener list to subclasses of Event, and gives back the same bytes for any
 * other class;</li>
 * <li>SideTransformer takes out fields and methods marked @SideOnly for the other side, and writes every other class out
 * again unchanged in content.</li>
 * </ul>
 * Each is put behind a wrapper that hands it only the classes that can have what it looks for: those whose constant
 * pool holds the method name, the annotation, or (for events) whose parent class is an Event. A class's methods,
 * annotations and calls can only name what its constant pool holds, so every other class comes out as before. With
 * -Dbetter112.checkFastPath=true every class goes through the original as well and any difference is counted.
 */
public class ForgeFastPath implements IClassTransformer {

    public static final int TERMINAL = 0, SUBSCRIBER = 1, SUBSCRIPTION = 2, SIDE = 3;
    private static final String[] IDS = {"forge_terminal", "forge_subscriber", "forge_subscription", "forge_side"};
    private static final String[] TARGETS = {
            "net.minecraftforge.fml.common.asm.transformers.TerminalTransformer",
            "net.minecraftforge.fml.common.asm.transformers.EventSubscriberTransformer",
            "net.minecraftforge.fml.common.asm.transformers.EventSubscriptionTransformer",
            "net.minecraftforge.fml.common.asm.transformers.SideTransformer"};
    private static final byte[] EXIT = ascii("exit"), HALT = ascii("halt");
    private static final byte[] SUBSCRIBE_EVENT = ascii("Lnet/minecraftforge/fml/common/eventhandler/SubscribeEvent;");
    private static final byte[] SIDE_ONLY = ascii("Lnet/minecraftforge/fml/relauncher/SideOnly;");
    private static final String EVENT = "net.minecraftforge.fml.common.eventhandler.Event";

    private static final boolean CHECK = Boolean.getBoolean("better112.checkFastPath");
    private static volatile boolean done;
    private static Field listField;
    /** Per kind: classes handed on, classes skipped, and (when checking) skipped classes the original changed. */
    private static final long[][] COUNTS = new long[4][3];

    private final IClassTransformer original;
    private final int kind;
    private volatile Class<?> event;
    private volatile ClassLoader eventLoader;

    protected ForgeFastPath(IClassTransformer original, int kind) {
        this.original = original;
        this.kind = kind;
    }

    /**
     * The wrapper put in the list in place of a transformer. Each wrapper's class name holds the name of the transformer
     * it stands for, because Mixin leaves TerminalTransformer and EventSubscriptionTransformer out of its own pass over
     * mixin classes by finding those names in the class names; the TerminalTransformer wrapper is also one, since
     * TickCentral orders the list and runs its own pass by that class.
     */
    private static IClassTransformer wrap(IClassTransformer original, int kind) {
        switch (kind) {
            case TERMINAL:
                return new better112.core.forge.net.minecraftforge.fml.common.asm.transformers.TerminalTransformer(new ForgeFastPath(original, kind));
            case SUBSCRIBER:
                return new better112.core.forge.net.minecraftforge.fml.common.asm.transformers.EventSubscriberTransformer(original);
            case SUBSCRIPTION:
                return new better112.core.forge.net.minecraftforge.fml.common.asm.transformers.EventSubscriptionTransformer(original);
            default:
                return new better112.core.forge.net.minecraftforge.fml.common.asm.transformers.SideTransformer(original);
        }
    }

    private static boolean isWrapper(IClassTransformer t) {
        return t instanceof ForgeFastPath || t instanceof better112.core.forge.net.minecraftforge.fml.common.asm.transformers.TerminalTransformer;
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * Puts the wrappers in place once Forge's last transformer (TerminalTransformer) is registered. Called before each
     * class this mod's transformer sees until then; the list is changed only by setting entries, which loops already
     * walking it allow.
     */
    @SuppressWarnings("unchecked")
    static void install() {
        if (done) {
            return;
        }
        synchronized (ForgeFastPath.class) {
            if (done) {
                return;
            }
            try {
                if (listField == null) {
                    Field f = net.minecraft.launchwrapper.LaunchClassLoader.class.getDeclaredField("transformers");
                    f.setAccessible(true);
                    listField = f;
                }
                List<IClassTransformer> list = (List<IClassTransformer>) listField.get(Launch.classLoader);
                boolean terminal = false;
                for (int i = 0; i < list.size(); i++) {
                    IClassTransformer t = list.get(i);
                    if (isWrapper(t)) {
                        terminal |= t instanceof better112.core.forge.net.minecraftforge.fml.common.asm.transformers.TerminalTransformer;
                        continue;
                    }
                    String name = t.getClass().getName();
                    for (int kind = 0; kind < TARGETS.length; kind++) {
                        if (name.equals(TARGETS[kind]) || name.equals("$wrapper." + TARGETS[kind])) {
                            if (kind == TERMINAL) {
                                terminal = true;
                            }
                            if (PatchState.enabled(IDS[kind])) {
                                list.set(i, wrap(t, kind));
                                PatchState.applied(IDS[kind]);
                            }
                        }
                    }
                }
                if (terminal) {
                    done = true;
                }
            } catch (Throwable t) {
                done = true;
                for (String id : IDS) {
                    PatchState.failed(id);
                }
                System.err.println("[Better112] Forge's transformers left as they are: " + t);
            }
        }
    }

    @Override
    public byte[] transform(String name, String transformedName, byte[] bytes) {
        if (bytes == null || !skippable(name, bytes)) {
            count(0);
            return original.transform(name, transformedName, bytes);
        }
        count(1);
        if (CHECK) {
            byte[] out = original.transform(name, transformedName, bytes);
            boolean differs = kind == SIDE ? !java.util.Arrays.equals(out, nodeRoundTrip(bytes)) : out != bytes;
            if (differs) {
                count(2);
                String dump = System.getProperty("better112.checkFastPathDump");
                if (dump != null && COUNTS[kind][2] <= 20) {
                    try {
                        java.io.File dir = new java.io.File(dump);
                        dir.mkdirs();
                        java.nio.file.Files.write(new java.io.File(dir, IDS[kind] + "_" + transformedName + "_original.class").toPath(), out);
                        java.nio.file.Files.write(new java.io.File(dir, IDS[kind] + "_" + transformedName + "_skipped.class").toPath(), bytes);
                    } catch (java.io.IOException e) {
                        // only for the check
                    }
                }
                System.err.println("[Better112] fast path check: " + IDS[kind] + " would have changed " + transformedName);
            }
            return out;
        }
        return bytes;
    }

    private void count(int what) {
        synchronized (COUNTS) {
            COUNTS[kind][what]++;
        }
    }

    /** Whether the original transformer would give back this class unchanged. False when not sure. */
    private boolean skippable(String name, byte[] bytes) {
        try {
            switch (kind) {
                case TERMINAL:
                    return !holdsUtf8(bytes, EXIT) && !holdsUtf8(bytes, HALT);
                case SUBSCRIBER:
                    return !holdsUtf8(bytes, SUBSCRIBE_EVENT);
                case SIDE:
                    return !holdsUtf8(bytes, SIDE_ONLY);
                case SUBSCRIPTION:
                    return !maybeEvent(name, bytes);
                default:
                    return false;
            }
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * EventSubscriptionTransformer gives back the bytes untouched for Event itself, for game classes and classes with no
     * package, and for any class whose parent class is not an Event; it loads the parent class to find out, through its
     * own class loader. The same is done here, without reading the rest of the class first.
     */
    private boolean maybeEvent(String name, byte[] bytes) {
        if (name.equals(EVENT) || name.startsWith("net.minecraft.") || name.indexOf('.') == -1) {
            return false;
        }
        String parent = superName(bytes);
        if (parent == null) {
            return true;
        }
        try {
            if (event == null) {
                IClassTransformer real = original;
                if (!(real.getClass().getName().equals(TARGETS[SUBSCRIPTION]))) {
                    Field f = real.getClass().getSuperclass().getDeclaredField("parent");
                    f.setAccessible(true);
                    real = (IClassTransformer) f.get(real);
                }
                ClassLoader loader = real.getClass().getClassLoader();
                event = Class.forName(EVENT, false, loader);
                eventLoader = loader;
            }
            Class<?> parentClass = eventLoader.loadClass(parent.replace('/', '.'));
            return event.isAssignableFrom(parentClass);
        } catch (Throwable t) {
            return true; // let the original find out, and report, as it always has
        }
    }

    // ---------------------------------------------------------------- constant pool

    /** Whether the class file's constant pool holds a UTF-8 entry equal to this ASCII text. */
    static boolean holdsUtf8(byte[] b, byte[] text) {
        int count = u2(b, 8);
        int at = 10;
        for (int i = 1; i < count; i++) {
            int tag = b[at] & 0xFF;
            switch (tag) {
                case 1: {
                    int length = u2(b, at + 1);
                    if (length == text.length && regionMatches(b, at + 3, text)) {
                        return true;
                    }
                    at += 3 + length;
                    break;
                }
                case 3: case 4: case 9: case 10: case 11: case 12: case 17: case 18:
                    at += 5;
                    break;
                case 5: case 6:
                    at += 9;
                    i++;
                    break;
                case 7: case 8: case 16: case 19: case 20:
                    at += 3;
                    break;
                case 15:
                    at += 4;
                    break;
                default:
                    throw new IllegalArgumentException("constant pool tag " + tag);
            }
        }
        return false;
    }

    private static boolean regionMatches(byte[] b, int at, byte[] text) {
        for (int i = 0; i < text.length; i++) {
            if (b[at + i] != text[i]) {
                return false;
            }
        }
        return true;
    }

    private static int u2(byte[] b, int at) {
        return ((b[at] & 0xFF) << 8) | (b[at + 1] & 0xFF);
    }

    /** The internal name of the class's parent class, or null for none. */
    private static String superName(byte[] bytes) {
        return new ClassReader(bytes).getSuperName();
    }

    /**
     * For the check: the class read into a tree and written out again as SideTransformer does (reader flags 0, writer
     * computing maximums), so that its output for a class it leaves alone can be compared byte for byte.
     */
    private static byte[] nodeRoundTrip(byte[] bytes) {
        org.objectweb.asm.tree.ClassNode node = new org.objectweb.asm.tree.ClassNode();
        new ClassReader(bytes).accept(node, 0);
        org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(org.objectweb.asm.ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    /** For the check: whether the original kept every field and method (SideTransformer only ever takes some out). */
    private static boolean sameMembers(byte[] before, byte[] after) {
        if (after == before) {
            return true;
        }
        return members(before).equals(members(after));
    }

    private static String members(byte[] bytes) {
        MemberLister lister = new MemberLister();
        new ClassReader(bytes).accept(lister, ClassReader.SKIP_CODE);
        return lister.out.toString();
    }

    private static final class MemberLister extends ClassVisitor {
        final StringBuilder out = new StringBuilder();

        MemberLister() {
            super(Opcodes.ASM5);
        }

        @Override
        public FieldVisitor visitField(int access, String name, String desc, String signature, Object value) {
            out.append('F').append(access).append(name).append(desc).append(';');
            return null;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
            out.append('M').append(access).append(name).append(desc).append(';');
            return null;
        }
    }

    /**
     * Loads this class (and the check's helper) before this mod's transformer is registered: a class that the
     * transformers need while they run must not itself have to pass through them.
     */
    static void touch() {
        if (CHECK) {
            new MemberLister();
        }
        Class<?>[] wrappers = {better112.core.forge.net.minecraftforge.fml.common.asm.transformers.TerminalTransformer.class,
                better112.core.forge.net.minecraftforge.fml.common.asm.transformers.EventSubscriberTransformer.class,
                better112.core.forge.net.minecraftforge.fml.common.asm.transformers.EventSubscriptionTransformer.class,
                better112.core.forge.net.minecraftforge.fml.common.asm.transformers.SideTransformer.class};
        for (Class<?> c : wrappers) {
            try {
                Class.forName(c.getName(), true, c.getClassLoader());
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /** Self-test: per wrapped transformer, classes handed on, skipped, and (when checking) skipped classes it changed. */
    public static String summary() {
        StringBuilder out = new StringBuilder();
        synchronized (COUNTS) {
            for (int kind = 0; kind < IDS.length; kind++) {
                out.append(IDS[kind]).append(": ").append(COUNTS[kind][0]).append(" handed on, ").append(COUNTS[kind][1]).append(" skipped");
                if (CHECK) {
                    out.append(", ").append(COUNTS[kind][2]).append(" changed by the original");
                }
                out.append(kind + 1 < IDS.length ? "; " : "");
            }
        }
        return out.toString();
    }
}
