/*******************************************************************************
 * Copyright (c) 2001, 2022 IBM Corp. and others
 *
 * This program and the accompanying materials are made available under
 * the terms of the Eclipse Public License 2.0 which accompanies this
 * distribution and is available at https://www.eclipse.org/legal/epl-2.0/
 * or the Apache License, Version 2.0 which accompanies this distribution and
 * is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * This Source Code may also be made available under the following
 * Secondary Licenses when the conditions for such availability set
 * forth in the Eclipse Public License, v. 2.0 are satisfied: GNU
 * General Public License, version 2 with the GNU Classpath
 * Exception [1] and GNU General Public License, version 2 with the
 * OpenJDK Assembly Exception [2].
 *
 * [1] https://www.gnu.org/software/classpath/license.html
 * [2] http://openjdk.java.net/legal/assembly-exception.html
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0 OR GPL-2.0 WITH Classpath-exception-2.0 OR LicenseRef-GPL-2.0 WITH Assembly-exception
 *******************************************************************************/
package j9vm.test.corehelper;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public final class DeadlockCoreGenerator {

	private static final class LockObject {

		final String name;

		LockObject(String name) {
			super();
			this.name = name;
		}

		@Override
		public String toString() {
			return "lock: " + name;
		}

	}

	/*
	 * **********
	 *   Legend:
	 * **********
	 *
	 * JOO: Java thread has object monitor, wants object monitor.
	 * JOS: Java thread has object monitor, wants system monitor.
	 * JSO: Java thread has system monitor, wants object monitor.
	 * JSS: Java thread has system monitor, wants system monitor.
	 * NSS: Native thread has system monitor, wants system monitor.
	 *
	 * N.B. A native thread can never hold/want an object monitor,
	 * because it must be attached first (i.e. it's a J9VMThread / Java thread).
	 */

	public static final long WAIT_TIME_MILIS = TimeUnit.SECONDS.toMillis(5);

	static {
		try {
			System.loadLibrary("j9ben");
		} catch (UnsatisfiedLinkError e) {
			System.out.println(e.getMessage());
			System.out.println("No natives for j9vm.test.ddrext.TestMonitors!");
			System.out.println("java.library.path: " + System.getProperty("java.library.path"));
			System.exit(1);
		}
	}

	public static void main(final String[] args) throws Exception {
		System.out.println("Running deadlock tester...");

		String testName = "testCase" + args[0];
		final Method testCase = DeadlockCoreGenerator.class.getDeclaredMethod(testName);

		Thread testThread = new Thread() {
			@Override
			public void run() {
				try {
					testCase.invoke(null);
				} catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
					e.printStackTrace();
				}
			}
		};

		testThread.setName("Java Deadlock Test Thread");
		testThread.start();

		// Wait for positive indication that threads are about to deadlock.
		waitForThreadsToApproachDeadlock(testName);

		System.out.println("Waiting...");
		Thread.sleep(WAIT_TIME_MILIS); // This is a guesstimate, but should be long enough to reach the desired state.

		try {
			throw new HelperExceptionForCoreGeneration();
		} catch (HelperExceptionForCoreGeneration e) {
			System.out.println("HelperExceptionForCoreGeneration is thrown and caught successfuly.");
			System.out.println("Forcefully terminating process.");
			// In all but the native-only deadlock, the JVM won't quit since not all threads died.
			System.exit(0); // Note that this will only run once the dump is done (exclusive!).
		}
	}

	/* *****************************
	 * Utility Methods for all Tests
	 * *****************************/

	public static native void setup();
	public static native void enterFirstMonitor();
	public static native void enterSecondMonitor();
	public static native void spawnNativeThread();
	public static native void createNativeDeadlock();

	private static final Object mutex = new LockObject("mutex");

	private static final Semaphore deadlock = new Semaphore(0);

	private static void aboutToDeadlock() {
		deadlock.release();
	}

	private static void waitForThreadsToApproachDeadlock(String testName) throws InterruptedException {
		int permits;

		switch (testName) {
		case "testCase4":
			permits = 1;
			break;
		case "testCase5":
			permits = 0;
			break;
		default:
			permits = 2;
			break;
		}

		if (permits != 0) {
			System.out.println("Waiting for threads to approach deadlock...");
			deadlock.acquire(permits);
		}
	}

	/* *****************************
	 *         Test Case #1
	 *       JOO, JOO deadlock.
	 * *****************************/

	public static void testCase1() throws InterruptedException {
		final Object firstLock = new LockObject("firstLock");
		final Object secondLock = new LockObject("secondLock");

		synchronized (firstLock) {
			Thread otherThread = new Thread() {
				@Override
				public void run() {
					System.out.println("testCase1 :: Secondary thread trying to acquire lock on secondLock");
					synchronized (secondLock) {
						synchronized (mutex) {
							mutex.notify();
						}
						System.out.println("testCase1 :: Secondary thread trying to acquire lock on firstLock");
						aboutToDeadlock();
						synchronized (firstLock) {
							System.out.println("Should not see me, testCase1()");
						}
					}
				}
			};

			synchronized (mutex) {
				otherThread.start();
				mutex.wait();
				System.out.println("testCase1 :: Main thread trying to acquire lock on secondLock");
				aboutToDeadlock();
				synchronized (secondLock) {
					System.out.println("Should not see me, testCase1().");
				}
			}
		}
	}

	/* *****************************
	 *         Test Case #2
	 *       JOS, JSO deadlock.
	 * *****************************/

	public static void testCase2() throws InterruptedException {
		setup();

		final Object javaObjMon = new LockObject("javaObjMon");

		synchronized (javaObjMon) {
			Thread otherThread = new Thread() {
				@Override
				public void run() {
					System.out.println("testCase2 :: Secondary thread trying to acquire lock on native monitor.");
					enterFirstMonitor();
					synchronized (mutex) {
						mutex.notify();
					}
					System.out.println("testCase2 :: Secondary thread trying to acquire lock on Java object monitor.");
					aboutToDeadlock();
					synchronized (javaObjMon) {
						System.out.println("Should not see me, testCase2()");
					}
				}
			};

			synchronized (mutex) {
				otherThread.start();
				mutex.wait();
				System.out.println("testCase2 :: Main thread trying to acquire lock on native monitor.");
				aboutToDeadlock();
				enterFirstMonitor(); // deadlock
				System.out.println("Should not see me, testCase2().");
			}
		}
	}

	/* *****************************
	 *         Test Case #3
	 *       JSS, JSS deadlock.
	 * *****************************/

	public static void testCase3() throws InterruptedException {
		setup();

		enterFirstMonitor();

		Thread otherThread = new Thread() {
			@Override
			public void run() {
				System.out.println("testCase3 :: Secondary thread trying to acquire lock on secondLock");
				enterSecondMonitor();
				synchronized (mutex) {
					mutex.notify();
				}
				System.out.println("testCase3 :: Secondary thread trying to acquire lock on firstLock");
				aboutToDeadlock();
				enterFirstMonitor(); // deadlock
				System.out.println("Should not see me, testCase3()");
			}
		};

		synchronized (mutex) {
			otherThread.start();
			mutex.wait(); // Make sure the other thread has the monitor we want to block on first.
			System.out.println("testCase3 :: Main thread trying to acquire lock on secondLock");
			aboutToDeadlock();
			enterSecondMonitor(); // deadlock
			System.out.println("Should not see me, testCase3().");
		}
	}

	/* *****************************
	 *         Test Case #4
	 *       JSS, NSS deadlock.
	 * *****************************/

	public static void testCase4() {
		setup();
		enterFirstMonitor();
		spawnNativeThread(); // Will return once second monitor has been acquired by the new native thread.
		aboutToDeadlock();
		enterSecondMonitor(); // deadlock
	}

	/* *****************************
	 *         Test Case #5
	 *       NSS, NSS deadlock.
	 * *****************************/

	public static void testCase5() {
		setup();
		createNativeDeadlock();
	}

	/* *****************************
	 *         Test Case #6
	 * Test JOS, NSS, JSO deadlock.
	 * *****************************/

	private static final Object lock1Test6 = new LockObject("lock1Test6");

	public static void testCase6() {
		setup(); // Initializes native monitors.

		Thread thread1test6 = new Thread() {
			@Override
			public void run() {
				synchronized (lock1Test6) { // Get O1
					System.out.println("spawnFirstThreadtest6 :: run()");
					spawnSecondThreadtest6();
					try {
						synchronized (mutex) {
							mutex.wait(); // Make sure second Java thread has the system monitor first!
						}
					} catch (InterruptedException e) {
						e.printStackTrace();
					}
					spawnNativeThread();
					// At this point the native thread acquired S2 and wants S1.
					System.out.println("spawnFirstThreadtest6 :: wait() indefinitely on enterSecondMonitor()");
					aboutToDeadlock();
					enterSecondMonitor(); // deadlock
					System.out.println("Should not see me, spawnFirstThreadtest6().");
				}
			}
		};
		thread1test6.start();
	}

	private static void spawnSecondThreadtest6() {
		Thread thread2test6 = new Thread() {
			@Override
			public void run() {
				System.out.println("spawnSecondThreadtest6 :: run()");
				enterFirstMonitor();
				synchronized (mutex) {
					mutex.notify();
				}
				System.out.println("spawnSecondThreadtest6 :: block indefinitely on lock1test6");
				aboutToDeadlock();
				synchronized (lock1Test6) { // deadlock
					System.out.println("Should not see me, spawnSecondThreadtest6().");
				}
			}
		};
		thread2test6.start();
	}

}
