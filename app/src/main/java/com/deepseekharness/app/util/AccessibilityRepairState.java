package com.deepseekharness.app.util;

import java.util.function.Predicate;

/**
 * Process-local, one-cycle accessibility repair. No persisted write or request can be replayed.
 *
 * <p>Repair only ever <em>adds</em> our own component back to {@code
 * enabled_accessibility_services}. It never removes the entry first: a remove-then-restore cycle
 * is itself a platform unbind on every ROM, and vendor ROMs (vivo/Funtouch and similar) read that
 * unbind back as a fresh disconnect, which turned one repair into an endless
 * "disable / re-enable" loop while the user sat in the system Settings screen.
 */
public final class AccessibilityRepairState {
  public enum CancelCause {
    SCREEN_AUTHORITY_REVOKED,
    POLICY_CHANGED,
    PAUSE_CHANGED,
    ADB_INTENT_CHANGED,
    DEVICE_SERVICE_STOPPED,
    PLATFORM_DISCONNECTED,
    EXECUTOR_UNAVAILABLE
  }

  public interface Ports {
    long now();

    boolean current();

    boolean permitted();

    boolean connected();

    String readEnabled() throws Exception;

    boolean writeEnabled(String value) throws Exception;

    boolean ownComponent(String value);

    void revokeScreenRequests();

    void diagnostic(long repairEpoch, String stage, Throwable failure);
  }

  public record Snapshot(long epoch, String phase, String code, boolean mayRemainDisabled) {}

  public static final class Attempt {
    public final String deviceInstanceId;
    private final long epoch;
    private final long deadline;
    private final Ports ports;
    private String ownEntry;
    private boolean writeClaimed;

    private Attempt(String deviceInstanceId, long epoch, long deadline, Ports ports) {
      this.deviceInstanceId = deviceInstanceId;
      this.epoch = epoch;
      this.deadline = deadline;
      this.ports = ports;
    }
  }

  /** One repair per window; re-armed on reconnect, on cancel and on every platform disconnect. */
  static final long COOLDOWN_MS = 60_000L;

  /** No repair has run yet; the first authenticated probe may start one immediately. */
  static final long NEVER = Long.MIN_VALUE;

  private long epoch;
  private long lastAttempt = NEVER;
  private Attempt active;
  private boolean uncertainWrite;
  private int writesInFlight;
  private Snapshot snapshot = new Snapshot(0, "IDLE", "NOT_ATTEMPTED", false);

  public synchronized Snapshot snapshot() {
    return snapshot;
  }

  public synchronized long intentEpoch() {
    return epoch;
  }

  /** Only the Android owner may offer a freshly authenticated, live-device proof. */
  public synchronized Attempt begin(String deviceInstanceId, Ports ports) {
    return begin(deviceInstanceId, ports, epoch);
  }

  public synchronized Attempt begin(String deviceInstanceId, Ports ports, long expectedEpoch) {
    long now = ports.now();
    if (epoch != expectedEpoch
        || active != null
        || writesInFlight != 0
        || uncertainWrite
        || (lastAttempt != NEVER && now - lastAttempt < COOLDOWN_MS)) return null;
    lastAttempt = now;
    Attempt next = new Attempt(deviceInstanceId, ++epoch, now + 5_000, ports);
    active = next;
    snapshot = new Snapshot(epoch, "CHECKING", "CHECKING_LIVE_FACTS", false);
    return next;
  }

  /**
   * Additive repair: append our own component when it is missing, never strip it first.
   *
   * <p>When the platform already lists us the list is left byte-for-byte untouched; only the
   * binding is late, and writing the same value back would itself be a rebind attempt.
   */
  public boolean enable(Attempt a) {
    synchronized (this) {
      if (!current(a) || a.writeClaimed) return false;
    }
    try {
      if (!live(a)) return false;
      String source = a.ports.readEnabled();
      String own = AccessibilityEnableList.OWN_COMPONENT;
      if (AccessibilityEnableList.containsOwn(source, own))
        return stop(a, "WAITING_CONNECTED", "ALREADY_ENABLED_AWAITING_CALLBACK");
      String target = AccessibilityEnableList.addOwn(source, own);
      a.ownEntry = own;
      synchronized (this) {
        if (!current(a) || a.writeClaimed) return false;
        a.writeClaimed = true;
        uncertainWrite = true;
        writesInFlight++;
        snapshot = new Snapshot(epoch, "ENABLE_SENT", "WRITE_MAY_HAVE_OCCURRED", true);
      }
      if (!writeOnce(a, target)) return stop(a, "UNKNOWN", "ENABLE_RESULT_UNKNOWN");
      synchronized (this) {
        if (current(a))
          snapshot =
              new Snapshot(epoch, "WAITING_CONNECTED", "AWAITING_REAL_SERVICE_CALLBACK", true);
      }
      return true;
    } catch (Exception failure) {
      return failed(a, "ENABLE", failure);
    }
  }

  private boolean writeOnce(Attempt a, String value) throws Exception {
    try {
      return a.ports.writeEnabled(value);
    } finally {
      synchronized (this) {
        writesInFlight--;
        // Returning retires the I/O fence, never the separate unknown-result fence.
      }
    }
  }

  private boolean live(Attempt a) {
    synchronized (this) {
      if (!current(a)) return false;
      if (a.ports.now() >= a.deadline) return stop(a, "TIMED_OUT", "REPAIR_DEADLINE");
    }
    if (!a.ports.current()) return stop(a, "CANCELLED", "DEVICE_OR_INTENT_CHANGED");
    if (!a.ports.permitted()) return stop(a, "UNAVAILABLE", "WRITE_SECURE_SETTINGS_MISSING");
    if (a.ports.connected()) return stop(a, "CANCELLED", "ALREADY_CONNECTED");
    synchronized (this) {
      return current(a);
    }
  }

  private synchronized boolean current(Attempt a) {
    return a != null && active == a && epoch == a.epoch;
  }

  private boolean failed(Attempt a, String stage, Throwable failure) {
    synchronized (this) {
      stop(a, a.writeClaimed ? "UNKNOWN" : "UNAVAILABLE", stage + "_PLATFORM_FAILURE");
    }
    // A diagnostic sink cannot change the domain result or hold the cancellation lock.
    try {
      a.ports.diagnostic(a.epoch, stage, failure);
    } catch (Throwable diagnosticUnavailable) {
      // The original failure and state take precedence over optional diagnostic storage.
    }
    return false;
  }

  private synchronized boolean stop(Attempt a, String phase, String code) {
    if (!current(a)) return false;
    active = null;
    snapshot = new Snapshot(++epoch, phase, code, a.writeClaimed);
    return false;
  }

  public synchronized void expire(Attempt a) {
    if (current(a) && a.ports.now() >= a.deadline) stop(a, "TIMED_OUT", "REPAIR_DEADLINE");
  }

  public synchronized void executorUnavailable(Attempt a) {
    if (current(a)) cancel(CancelCause.EXECUTOR_UNAVAILABLE);
  }

  public synchronized void cancel(CancelCause cause) {
    Attempt old = active;
    active = null;
    snapshot =
        new Snapshot(
            ++epoch, "CANCELLED", cause.name(), old != null ? old.writeClaimed : uncertainWrite);
  }

  public synchronized void deviceStopped(String deviceInstanceId) {
    if (active != null && active.deviceInstanceId.equals(deviceInstanceId))
      cancel(CancelCause.DEVICE_SERVICE_STOPPED);
  }

  /**
   * A platform disconnect has no user-origin identity, so it must never start a repair: it only
   * cancels an in-flight cycle and re-arms the cooldown so the next authenticated probe cannot
   * immediately write again.
   */
  public synchronized void platformDisconnected() {
    // Never write from a disconnect: only fence an in-flight cycle that has not written yet.
    if (active != null && !active.writeClaimed) cancel(CancelCause.PLATFORM_DISCONNECTED);
    // Re-arm the window once a repair ran, so a disconnect cannot chain a second one.
    if (lastAttempt != NEVER) lastAttempt = 0;
  }

  /** Called exclusively by the real AccessibilityService.onServiceConnected callback. */
  public synchronized void connected() {
    active = null;
    if (lastAttempt != NEVER) lastAttempt = 0;
    // A callback cannot retire a still-running old write, which might complete afterwards.
    uncertainWrite = writesInFlight != 0;
    if (uncertainWrite) writesInFlight = 0;
    snapshot =
        new Snapshot(
            ++epoch,
            "CONNECTED",
            uncertainWrite ? "REAL_SERVICE_CONNECTED_WRITE_PENDING" : "REAL_SERVICE_CONNECTED",
            uncertainWrite);
  }
}
