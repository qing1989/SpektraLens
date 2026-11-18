package app.spektrafilm;

import java.util.List;

public final class EditClipboardPolicyTest {
  private static int checks;

  private static void check(boolean value, String message) {
    checks++;
    if (!value) throw new AssertionError(message);
  }

  public static void main(String[] args) {
    check("开始编辑".equals(EditClipboardPolicy.editMenuLabel(false)), "empty edit label");
    check("再次编辑".equals(EditClipboardPolicy.editMenuLabel(true)), "edited edit label");
    check(!EditClipboardPolicy.canPaste(false), "empty clipboard cannot paste");
    check(EditClipboardPolicy.canPaste(true), "copied parameters can paste");
    check(!EditClipboardPolicy.acceptsAnotherSelection(20), "20 is the selection ceiling");
    check(EditClipboardPolicy.acceptsAnotherSelection(19), "19 accepts one more");
    check(
        !EditClipboardPolicy.canBatchPaste(true, 0),
        "batch paste needs at least one target");
    check(EditClipboardPolicy.canBatchPaste(true, 20), "batch paste accepts 20 targets");
    check(!EditClipboardPolicy.canBatchPaste(true, 21), "batch paste rejects 21 targets");
    check(
        !EditClipboardPolicy.canBatchPaste(false, 1),
        "batch paste needs copied parameters");
    check(
        "确认粘贴 · 7 张".equals(EditClipboardPolicy.batchButtonLabel(7)),
        "batch button exposes the selected count");
    List<String> twenty = java.util.Collections.nCopies(20, "frame");
    java.util.ArrayList<String> ids = new java.util.ArrayList<>();
    for (int i = 0; i < 20; i++) ids.add("id-" + i);
    check(
        EditClipboardPolicy.validBatch("{}", twenty, ids, "source"),
        "valid atomic batch");
    check(
        !EditClipboardPolicy.validBatch(" ", twenty, ids, "source"),
        "blank clipboard is rejected");
    ids.add("id-20");
    check(
        !EditClipboardPolicy.validBatch("{}", twenty, ids, "source"),
        "target and id lists must agree");
    check(
        !EditClipboardPolicy.validBatch(
            "{}", java.util.Collections.nCopies(21, "frame"), ids, "source"),
        "atomic batch rejects 21");
    System.out.println("EditClipboardPolicy: " + checks + " checks passed");
  }
}
