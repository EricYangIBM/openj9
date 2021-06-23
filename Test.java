import jdk.incubator.foreign.*;
import java.lang.reflect.*;
import java.lang.ref.Cleaner;
import java.security.ProtectionDomain;
import java.security.PrivilegedAction;

import org.objectweb.asm.*;
import static org.objectweb.asm.Opcodes.*;

public class Test {
    public static byte[] dump() throws Exception {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
		MethodVisitor mv;
		AnnotationVisitor av0;

        cw.visit(V16, ACC_PUBLIC + ACC_SUPER, "jdk/internal/misc/RunInScoped", null, "java/lang/Object", null);
        {
			mv = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
			mv.visitCode();
			mv.visitVarInsn(ALOAD, 0);
			mv.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V");
			mv.visitInsn(RETURN);
            mv.visitMaxs(0, 0);
			mv.visitEnd();
		}
        {
			/* Method does the following: 
			 * 
			 * @Scoped		// <-- package private annotation that isn't visible here.  This is why we use ASM to generate the class
			 * public static void runInScoped(Runnable r, Scope scope) {
			 *      r.run();
			 * }
			 * 
			 * */
			mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "runInScoped", "(Ljava/lang/Runnable;)V", null, null);
			{
				av0 = mv.visitAnnotation("Ljdk/internal/misc/ScopedMemoryAccess$Scoped;", true);
				av0.visitEnd();
			}
			mv.visitCode();
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKEINTERFACE, "java/lang/Runnable", "run", "()V", true);
			mv.visitInsn(RETURN);
            mv.visitMaxs(0, 0);
			mv.visitEnd();
		}
		cw.visitEnd();

        return cw.toByteArray();
    }

    public static void main(String[] args) {
        try {
            Class c = Class.forName("jdk.internal.foreign.MemoryScope");
            Method createShared = c.getDeclaredMethod("createShared", new Class[] {Object.class, Runnable.class, Cleaner.class});
            createShared.setAccessible(true);
            Method close = c.getDeclaredMethod("close");
            close.setAccessible(true);

            Class Scope = Class.forName("jdk.internal.misc.ScopedMemoryAccess$Scope");

            Class SMA = Class.forName("jdk.internal.misc.ScopedMemoryAccess");
            Method getScopedMemoryAccess = SMA.getDeclaredMethod("getScopedMemoryAccess");
            getScopedMemoryAccess.setAccessible(true);

            Thread t = new Thread();

            Object sma = getScopedMemoryAccess.invoke(null);
            final Object scope = createShared.invoke(null, null, t, null);
            Object base = new byte[20];

            final byte[] classBytes = dump();

            ClassLoader loader = ClassLoader.getSystemClassLoader();
            Class cls = Class.forName("java.lang.ClassLoader");
            Method defineClass = cls.getDeclaredMethod(
                            "defineClass", 
                            new Class[] { String.class, byte[].class, int.class, int.class });

            // Protected method invocation.
            defineClass.setAccessible(true);
            
            Object[] dcArgs = new Object[] {"jdk.internal.misc.RunInScoped", classBytes, 0, classBytes.length};
            Class RunInScoped = (Class)defineClass.invoke(loader, dcArgs);
            Method runInScoped = RunInScoped.getDeclaredMethod("runInScoped", new Class[] {Runnable.class});
            
            // Class Unsafe = Class.forName("jdk.internal.misc.Unsafe");
            // Method getUnsafe = Unsafe.getDeclaredMethod("getUnsafe");
            // getUnsafe.setAccessible(true);
            // Method defineClass = Unsafe.getDeclaredMethod("defineClass", new Class[] {String.class, byte[].class, int.class, int.class, ClassLoader.class, ProtectionDomain.class});
            // defineClass.setAccessible(true);

            // Object unsafe = getUnsafe.invoke(null);

            // ClassLoader loader = Test.class.getClassLoader();
            // Class<?> RunInScoped = (Class<?>)defineClass.invoke(unsafe, "RunInScoped", classBytes, 0, classBytes.length, loader, Test.class.getProtectionDomain());
            
            // Method runInScoped = RunInScoped.getDeclaredMethod("runInScoped", new Class[] {Runnable.class, Scope});

            Synch synch1 = new Synch();
            Synch synch2 = new Synch();

            Thread t1 = new Thread(()->{
                try {
                    synchronized (synch1) {
                        synch1.wait();
                    }
                    close.invoke(scope);
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    synchronized (synch2) {
                        synch2.notify();
                    }
                }
            }, "Thread1");
            Thread t2 = new Thread(()->{
                try {
                    runInScoped.invoke(null, new Thread(()->{
                        try {
                            //Thread.dumpStack();
                            Object temp = scope;
                            synchronized (synch1) {
                                synch1.notify();
                            }
                            synchronized (synch2) {
                                synch2.wait();
                            }
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }));
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }, "Thread2");

            t1.start();
            t2.start();

            

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}

public class Synch {}
