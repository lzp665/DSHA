package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

/**
 * Development-only OS facts; the production state, cancellation and dispatch code is real.
 *
 * <p>The behavioural contract under test is additive repair: a cycle may append our own component
 * and must never remove it, so a repair cannot be the unbind it is trying to recover from.
 */
public class AccessibilityRepairStateTest {
  private static final String OWN = AccessibilityEnableList.OWN_COMPONENT;
  private static final String OTHER = "other/service";

  private static final class Facts implements AccessibilityRepairState.Ports {
    long time;
    boolean current = true;
    boolean permitted = true;
    boolean connected;
    boolean writeResult = true;
    boolean throwWrite;
    boolean diagnosticThrows;
    Throwable diagnosticFailure;
    String diagnosticStage;
    Exception writeFailure =
        new Exception("unknown Binder outcome", new IllegalStateException("origin"));
    String value = "first/service:" + OTHER + ":last/service";
    String nextRead;
    int revoked;
    List<String> writes = new ArrayList<>();
    CountDownLatch entered;
    CountDownLatch release;

    public long now() {
      return time;
    }

    public boolean current() {
      return current;
    }

    public boolean permitted() {
      return permitted;
    }

    public boolean connected() {
      return connected;
    }

    public String readEnabled() {
      // A queued value is what this next read sees; it only has to be consumed once.
      if (nextRead != null) {
        value = nextRead;
        nextRead = null;
      }
      return value;
    }

    public boolean writeEnabled(String next) {
      writes.add(next);
      if (entered != null) {
        entered.countDown();
        try {
          assertTrue(release.await(2, TimeUnit.SECONDS));
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
        }
      }
      if (throwWrite) throw new IllegalStateException(writeFailure);
      if (!writeResult) return false;
      value = next;
      return true;
    }

    public boolean ownComponent(String v) {
      return OWN.equals(v);
    }

    public void revokeScreenRequests() {
      revoked++;
    }

    public void diagnostic(long repairEpoch, String stage, Throwable failure) {
      diagnosticStage = stage;
      diagnosticFailure = failure;
      if (diagnosticThrows) throw new IllegalStateException("sink unavailable");
    }
  }

  @Test
  public void repairAppendsOwnEntryWithoutEverRemovingIt() {
    var s = new AccessibilityRepairState();
    var f = new Facts();
    assertTrue(s.enable(s.begin("device", f)));
    assertEquals(List.of("first/service:" + OTHER + ":last/service:" + OWN), f.writes);
    assertEquals("WAITING_CONNECTED", s.snapshot().phase());
    // One cycle only: a second write would be a second rebind attempt.
    assertFalse(s.enable(s.begin("device", f)));
    assertEquals(1, f.writes.size());
    s.connected();
    assertEquals("CONNECTED", s.snapshot().phase());
    assertFalse(s.snapshot().mayRemainDisabled());
  }

  @Test
  public void alreadyListedServiceIsNeverRewritten() {
    var s = new AccessibilityRepairState();
    var f = new Facts();
    f.value = "first/service:" + OWN;
    assertFalse(s.enable(s.begin("d", f)));
    assertTrue(f.writes.isEmpty());
    assertEquals("ALREADY_ENABLED_AWAITING_CALLBACK", s.snapshot().code());
  }

  @Test
  public void noPermissionIsExplicitlyUnavailable() {
    var s = new AccessibilityRepairState();
    var f = new Facts();
    f.permitted = false;
    assertFalse(s.enable(s.begin("d", f)));
    assertEquals("WRITE_SECURE_SETTINGS_MISSING", s.snapshot().code());
    assertTrue(f.writes.isEmpty());
  }

  @Test
  public void staleDeviceOrAdbIntentNeverWrites() {
    var s = new AccessibilityRepairState();
    var f = new Facts();
    var a = s.begin("d", f);
    f.current = false;
    assertFalse(s.enable(a));
    assertTrue(f.writes.isEmpty());
  }

  @Test
  public void everyExplicitCancelCauseFencesQueuedWork() {
    for (var cause : AccessibilityRepairState.CancelCause.values()) {
      var s = new AccessibilityRepairState();
      var f = new Facts();
      var a = s.begin("d", f);
      s.cancel(cause);
      assertFalse(s.enable(a));
      assertTrue(f.writes.isEmpty());
      assertEquals(cause.name(), s.snapshot().code());
    }
  }

  @Test
  public void disconnectBeforeTheWriteCancelsWithoutTouchingTheList() {
    var s = new AccessibilityRepairState();
    var f = new Facts();
    var a = s.begin("d", f);
    s.platformDisconnected();
    assertFalse(s.enable(a));
    assertTrue(f.writes.isEmpty());
    assertEquals("PLATFORM_DISCONNECTED", s.snapshot().code());
  }

  @Test
  public void platformDisconnectRearmsTheCooldownInsteadOfQueueingAnotherWrite() {
    var s = new AccessibilityRepairState();
    var f = new Facts();
    assertTrue(s.enable(s.begin("d", f)));
    s.connected();
    // A disconnect right after a successful repair may not start a fresh cycle immediately.
    s.platformDisconnected();
    f.value = "first/service:" + OTHER + ":last/service";
    // A fresh real connection re-arms the window; the disconnect itself never did.
    s.connected();
    // The window starts when the repair began, so advance past it from a fresh clock.
    f.time = 60_001;
    var next = s.begin("d", f);
    assertNotNull(next);
    // Additive repair appends our entry again; the disconnect itself never wrote anything.
    assertTrue(s.enable(next));
    assertEquals(2, f.writes.size());
    assertEquals("first/service:" + OTHER + ":last/service:" + OWN, f.value);
  }

  @Test
  public void unknownWriteResultNeverRetriesInsideTheCooldownWindow() {
    for (boolean throwsFailure : new boolean[] {false, true}) {
      var s = new AccessibilityRepairState();
      var f = new Facts();
      f.writeResult = false;
      f.throwWrite = throwsFailure;
      assertFalse(s.enable(s.begin("d", f)));
      assertEquals("UNKNOWN", s.snapshot().phase());
      assertEquals(1, f.writes.size());
      f.time = 59_999;
      assertNull(s.begin("d", f));
      f.time = 60_000;
      s.connected();
      assertNotNull(s.begin("d", f));
    }
  }

  @Test
  public void deadlineBeforeWriteHasNoSideEffect() {
    var s = new AccessibilityRepairState();
    var f = new Facts();
    var a = s.begin("d", f);
    f.time = 5_000;
    s.expire(a);
    assertFalse(s.enable(a));
    assertTrue(f.writes.isEmpty());
  }

  @Test
  public void revokeAndDeadlineDoNotWaitForBlockedPlatformWrite() throws Exception {
    for (boolean deadline : new boolean[] {false, true}) {
      var s = new AccessibilityRepairState();
      var f = new Facts();
      f.entered = new CountDownLatch(1);
      f.release = new CountDownLatch(1);
      var a = s.begin("d", f);
      Thread worker = new Thread(() -> s.enable(a));
      worker.start();
      assertTrue(f.entered.await(1, TimeUnit.SECONDS));
      if (deadline) {
        f.time = 5_000;
        s.expire(a);
      } else s.cancel(AccessibilityRepairState.CancelCause.POLICY_CHANGED);
      assertEquals(deadline ? "TIMED_OUT" : "CANCELLED", s.snapshot().phase());
      assertTrue(s.snapshot().mayRemainDisabled());
      // Even a real new connection cannot retire the still-blocked old platform call.
      s.connected();
      f.time = 60_000;
      assertNull(s.begin("replacement", f));
      f.release.countDown();
      worker.join(1_000);
      assertFalse(worker.isAlive());
      assertEquals(1, f.writes.size());
      // A callback preceding the write return cannot clear its unknown-result fence, and the
      // settled epoch still refuses a fresh cycle inside the cooldown window.
      assertNull(s.begin("replacement", f));
      s.connected();
      assertNull(s.begin("replacement", f));
      f.time = 60_001;
      s.connected();
      assertNotNull(s.begin("replacement", f));
    }
  }

  @Test
  public void oldDeviceStopCannotCancelReplacementInstance() {
    var s = new AccessibilityRepairState();
    var f = new Facts();
    var a = s.begin("new", f);
    s.deviceStopped("old");
    assertTrue(s.enable(a));
    s.deviceStopped("new");
    assertEquals(1, f.writes.size());
  }

  @Test
  public void revocationBeforeDelayedAuthenticatedOfferRejectsOldRepairEpoch() {
    var s = new AccessibilityRepairState();
    var f = new Facts();
    long probeEpoch = s.intentEpoch();
    s.cancel(AccessibilityRepairState.CancelCause.POLICY_CHANGED);
    assertNull(s.begin("d", f, probeEpoch));
    assertTrue(f.writes.isEmpty());
  }

  @Test
  public void connectedFactAloneDoesNotDeclareCallbackSuccess() {
    var s = new AccessibilityRepairState();
    var f = new Facts();
    f.connected = true;
    assertFalse(s.enable(s.begin("d", f)));
    assertNotEquals("CONNECTED", s.snapshot().phase());
    assertTrue(f.writes.isEmpty());
  }

  @Test
  public void settingsChangeDuringPreparationStillProducesAnAdditiveWrite() {
    var s = new AccessibilityRepairState();
    var f = new Facts();
    f.nextRead = "new/service";
    assertTrue(s.enable(s.begin("d", f)));
    // The write rebuilds from the freshest read, so a concurrent edit is preserved.
    assertEquals(List.of("new/service:" + OWN), f.writes);
  }

  @Test
  public void otherServicesSurviveRepairVerbatim() {
    var s = new AccessibilityRepairState();
    var f = new Facts();
    f.value = "a/x:b/y";
    assertTrue(s.enable(s.begin("d", f)));
    assertEquals("a/x:b/y:" + OWN, f.value);
  }
}
