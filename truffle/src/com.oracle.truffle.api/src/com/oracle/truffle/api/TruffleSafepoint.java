/*
 * Copyright (c) 2015, 2018, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.oracle.truffle.api;

import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.Lock;

import org.graalvm.polyglot.Context;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.impl.Accessor.EngineSupport;
import com.oracle.truffle.api.impl.ThreadLocalHandshake;
import com.oracle.truffle.api.nodes.LoopNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;

/**
 * Truffle safepoints allow interrupting the guest language execution to execute thread-local
 * actions submitted by a language or tool. A safepoint is a location during the guest language
 * execution where the state is consistent, and other operations can read its state.
 * <p>
 * <h3>Supporting Safepoints in a Language</h3>
 *
 * Safepoints are explicitly polled by invoking the {@link #poll(Node)} method. Safepoints are
 * {@link #poll(Node) polled} with loose location or with {@link #pollHere(Node) exact location}. A
 * poll with a loose location is significantly more efficient than a poll with a precise location as
 * the compiler is able to move and combine the poll requests during compilation. A Truffle guest
 * language implementation must ensure that a safepoint is polled repeatedly within a constant time
 * interval. For example, a single arithmetic expression completes within a constant number of CPU
 * cycles. However, a loop that summarizes values over an array uses a non-constant time dependent
 * on the array size. This typically means that safepoints are best polled at the end of loops and
 * at the end of function or method calls to cover recursion. In addition, any guest language code
 * that blocks the execution, like guest language locks, need to use the
 * {@link #setBlocked(Interrupter) blocking API} to allow polling of safepoints while the thread is
 * waiting.
 * <p>
 * Truffle's {@link LoopNode loop node} and {@link RootNode root node} support safepoint polling
 * automatically. No further calls to {@link #poll(Node)} are therefore necessary. Custom loops or
 * loops behind {@link TruffleBoundary boundary} annotated method calls are expected to be notified
 * by the guest language implementation manually.
 * <p>
 * Thread local actions optionally incur side-effects. By default side-effects are enabled. A
 * language implementation may disable side-effects temporarily for the current thread using
 * {@link #setAllowSideEffects(boolean)} method.
 * <p>
 *
 * <h3>Submitting thread local actions</h3>
 *
 * See {@link ThreadLocalAction} for details on how to submit actions.
 * <p>
 * Further information can be found in the
 * <a href="http://github.com/oracle/graal/blob/master/truffle/docs/Safepoints.md">safepoint
 * tutorial</a>.
 *
 * @see ThreadLocalAction
 * @see Context#safepoint()
 * @since 21.1
 */
public abstract class TruffleSafepoint {

    private static final ThreadLocalHandshake HANDSHAKE = LanguageAccessor.ACCESSOR.runtimeSupport().getThreadLocalHandshake();

    /**
     * Do not extend this class. This class is intended to be implemented by a Truffle runtime
     * implementation.
     *
     * @since 21.1
     */
    protected TruffleSafepoint(EngineSupport support) {
        if (support == null) {
            throw new AssertionError("Only runtime is allowed create truffle safepoint instances.");
        }
    }

    /**
     * Polls a safepoint at the provided location. This allows to run thread local actions at this
     * location. A Truffle guest language implementation must ensure that a safepoint is polled
     * repeatedly within a constant time interval. See {@link TruffleSafepoint} for further details.
     * <p>
     * In compiled code calls to this method are removed. Instead the compiler inserts safepoints
     * automatically at loop ends and method exits. In this case the node location is approximated
     * by frame state of method ends and loop exits in the compiler IR. For method ends the parent
     * root node and for loop exits the loop node is passed as location.
     * <p>
     * Guest language exceptions may be thrown by this method. If
     * {@link #setAllowSideEffects(boolean) side-effects} are allowed then also guest language
     * exceptions may be thrown. Otherwise only internal or {@link ThreadDeath thread-death}
     * exceptions may be thrown. This method is safe to be used on compiled code paths.
     * <p>
     * Example usage with an unbounded loop sum behind a {@link TruffleBoundary}.
     *
     * <pre>
     * &#64;TruffleBoundary
     * int sum(int[] array) {
     *     int sum = 0;
     *     for (int i = 0; i < array.length; i++) {
     *         sum += array[i];
     *
     *         TruffleSafepoint.poll();
     *     }
     *     return sum;
     * }
     * </pre>
     *
     * @param location the location of the poll. Must not be <code>null</code>.
     * @see TruffleSafepoint
     * @see #pollHere(Node)
     * @since 21.1
     */
    public static void poll(Node location) {
        Objects.requireNonNull(location);
        HANDSHAKE.poll(location);
    }

    /**
     * Similar to {@link #poll(Node)} but with exact location. A poll with {@link #poll(Node) loose
     * location} is significantly more efficient than a poll with precise location as the compiler
     * is able to move and combine the poll requests during compilation. This method is safe to be
     * used on compiled code paths.
     * <p>
     * Usage example:
     *
     * <pre>
     * TruffleSafepoint safepoint = TruffleSafepoint.getCurrent();
     * boolean prev = safepoint.setAllowSideEffects(false);
     * try {
     *     // criticial section
     * } finally {
     *     safepoint.setAllowSideEffects(prev);
     *     TruffleSafepoint.pollHere(this);
     * }
     * </pre>
     *
     * @param location the location of the poll. Must not be <code>null</code>.
     * @see #poll(Node)
     * @since 21.1
     */
    @TruffleBoundary
    public static void pollHere(Node location) {
        Objects.requireNonNull(location);
        HANDSHAKE.poll(location);
    }

    /**
     * Sets the current thread into a blocked state or clears it from blocked state. Setting the
     * blocked state allows safepoint notification while the current thread is blocked. This allows
     * Truffle to interrupt the lock temporarily to perform a thread local action. This method is
     * safe to be used on compiled code paths.
     * <p>
     * Example usage:
     * <p>
     * Note there is a short-cut method to achieve a the same behavior as in this example
     * {@link #setBlockedInterruptable(Node, ThreadInterruptable, Object)}.
     *
     * <pre>
     * Lock lock = new ReentrantLock();
     * TruffleSafepoint sp = TruffleSafepoint.getCurrent();
     * Interrupter prev = sp.setBlocked(Interrupter.THREAD_INTERRUPT);
     * try {
     *     while (true) {
     *         try {
     *             lock.lockInterruptibly();
     *             break;
     *         } catch (InterruptedException e) {
     *             TruffleSafepoint.poll(this);
     *         }
     *     }
     * } finally {
     *     sp.setBlocked(prev);
     * }
     * </pre>
     *
     * @see TruffleSafepoint
     * @param interrupter the interrupter to interrupt the current thread
     * @since 21.1
     */
    public abstract Interrupter setBlocked(Interrupter interrupter);

    /**
     * Short-cut method to allow setting the blocked status for methods that throw
     * {@link InterruptedException} and support interrupting using {@link Thread#interrupt()}.
     * <p>
     *
     * <pre>
     * TruffleSafepoint.setBlockedInterruptable(location, Lock::lockInterruptibly, lock);
     * </pre>
     *
     * This method is short-hand for:
     *
     * <pre>
     * TruffleSafepoint sp = TruffleSafepoint.getCurrent();
     * Interrupter prev = sp.setBlocked(Interrupter.THREAD_INTERRUPT);
     * try {
     *     while (true) {
     *         try {
     *             interruptable.run(lock);
     *             break;
     *         } catch (InterruptedException e) {
     *             TruffleSafepoint.poll(this);
     *         }
     *     }
     * } finally {
     *     sp.setBlocked(prev);
     * }
     * </pre>
     *
     *
     * @param location the location with which the safepoint should be polled.
     * @param interruptable the thread interruptable method to use for locking the object
     * @param object the instance to use the interruptable method with.
     *
     * @since 21.1
     */
    @TruffleBoundary
    public static <T> void setBlockedInterruptable(Node location, ThreadInterruptable<T> interruptable, T object) {
        TruffleSafepoint safepoint = TruffleSafepoint.getCurrent();
        Interrupter prev = safepoint.setBlocked(Interrupter.THREAD_INTERRUPT);
        try {
            while (true) {
                try {
                    interruptable.apply(object);
                    break;
                } catch (InterruptedException e) {
                    TruffleSafepoint.poll(location);
                }
            }
        } finally {
            safepoint.setBlocked(prev);
        }
    }

    /**
     * Allows to temporarily disable side-effecting thread local notifications on this thread. It is
     * recommended to disable side-effecting actions only for a short and constant period of time.
     * While safe-points are disabled it is guaranteed that:
     * <ul>
     * <li>No guest language objects are modified or other guest language code is running.
     * <li>No guest language exceptions are thrown. Note that internal errors might be thrown.
     * </ul>
     * <p>
     * Example usage:
     *
     * <pre>
     * TruffleSafepoint sp = TruffleSafepoint.getCurrent();
     * boolean prev = sp.setAllowSideEffects(false);
     * try {
     *     // criticial section
     * } finally {
     *     sp.setAllowSideEffects(prev);
     * }
     * </pre>
     *
     * @since 21.1
     */
    public abstract boolean setAllowSideEffects(boolean enabled);

    /**
     * Returns the current safepoint configuration for the current thread. This method is useful to
     * access configuration methods like {@link #setBlocked(Interrupter)} or
     * {@link #setAllowSideEffects(boolean)}.
     * <p>
     * Important: The result of this method must not be stored or used on a different thread than
     * the current thread.
     *
     * @since 21.1
     */
    public static TruffleSafepoint getCurrent() {
        return HANDSHAKE.getCurrent();
    }

    /**
     * Function interface that represent interruptable Java methods. Examples are
     * {@link Lock#lockInterruptibly() Lock::lockInterruptibly} or {@link Semaphore#acquire()
     * Semaphore::acquire}.
     *
     * @see TruffleSafepoint#setBlockedInterruptable(Node, ThreadInterruptable, Object)
     * @since 21.1
     */
    @FunctionalInterface
    public interface ThreadInterruptable<T> {

        /**
         * Runs the interruptable method for a given object.
         *
         * @since 21.1
         */
        void apply(T arg) throws InterruptedException;

    }

    /**
     * An interrupter allows a foreign thread to interrupt the execution on a separate thread. Used
     * to allo the Truffle safepoint mechanism to interrupt a blocked thread and schedule a
     * safepoint.
     *
     * @see TruffleSafepoint#setBlocked(Interrupter)
     * @see Interrupter#THREAD_INTERRUPT
     * @since 21.1
     */
    public interface Interrupter {

        /**
         * Sets the interrupted state on a foreign thread. Implementations of this method must not
         * acquire any other locks or run guest language code, as an implementation specific lock is
         * held while executing.
         *
         * @param thread the thread to interrupt
         *
         * @since 21.1
         */
        void interrupt(Thread thread);

        /**
         * Resets the interrupted state when executing on a thread after the thread was interrupted.
         * If a thread was interrupted it is guaranteed to be reset at least once, but might be
         * reset multiple times.
         *
         * @since 21.1
         */
        void resetInterrupted();

        /**
         * A thread interrupter implementation that uses {@link Thread#interrupt()} and
         * {@link Thread#interrupted()} to clear the thread state.
         * <p>
         *
         * <pre>
         * THREAD_INTERRUPT = new Interrupter() {
         *
         *     &#64;Override
         *     public void interrupt(Thread t) {
         *         t.interrupt();
         *     }
         *
         *     &#64;Override
         *     public void interrupted() {
         *         Thread.interrupted();
         *     }
         *
         * };
         * </pre>
         *
         * @since 21.1
         */
        Interrupter THREAD_INTERRUPT = new Interrupter() {

            @Override
            public void resetInterrupted() {
                Thread.interrupted();
            }

            @Override
            public void interrupt(Thread t) {
                t.interrupt();
            }
        };

    }

}
