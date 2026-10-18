# Proposed capture-window amendment — awaiting owner approval

This is a proposal, not the active contract. Do not implement dependent changes until approved.

## Evidence and purpose

CaptureRun and the Field guide take BACKGROUND at the target, after flight. A diagnostic through the actual SC1 puff world and sensor chain produced SO2 background 2716.444879 ppb and collection 3378.454094 ppb (difference 662.009215 ppb). These values are one diagnostic run, not fixed calibration data. A background at a plume target is not a clean-air reference. The present ranker predicts full plume enhancement, so simply feeding it that difference would be inconsistent.

Keep the published mission procedure. Represent the actual difference and predict the same difference. This avoids silently inventing clean air or changing the flight route.

## Exact change to AGREEMENT section 9

Replace:

```java
public interface CaptureRules { ChannelVerdict judge(CaptureInput input); }
```

with:

```java
public interface CaptureRules {
    ChannelVerdict judge(CaptureInput input);
    default List<Window> windows(CaptureInput input) { return List.of(); }
}
```

Add the following paragraphs:

`CaptureRules.windows(input)` computes a background window and a complete 30-second collection window for one channel from the attempt's recorded stages, gas readings and branch-flow readings. It does not save records or make the human review decision. Both windows use server UUID v4 IDs. All contributing readings must have the same instrument, gas and origin. Unknown background, insufficient valid consecutive collection, missing or invalid flow, and unsupported channels produce no usable collection window and an explicit diagnostic reason in the capture review result.

The field `review_capture` handler calls this function only for a channel whose automatic pre-check and human decision are both VALID. It obtains the raw input from the saved reading records belonging to the mission vehicle and the attempt's stage intervals. It saves the returned windows with the screening record in the same command transaction. Repeating a command returns its existing receipt and creates no new windows. REJECTED, LIMITED, interrupted and unreviewed capture channels remain available as raw readings, attempts and screening records, but are not admitted as usable capture windows by this amendment.

For a vehicle collection window, `mean_enhancement` is the measured collection mean minus the measured BACKGROUND-stage mean. It is a temporal measurement contrast, not a claim that the background was plume-free. The collection window cites its saved background through `background_window_id`. Its uncertainty includes both averaging operations and their correlation under the declared synthetic noise model.

Science predicts this same collection-minus-background contrast, propagating the gas-specific transport, chamber and sensor response through the recorded inlet history. It must not compare a temporal contrast against an absolute steady concentration. Missing background records or required inlet history exclude the window with a reason. Analyses using vehicle contrasts declare the possible plume content of the background and the finite-history initialization assumption in `limitations[]`.

## Exact addition to CORE-API

Under the capture-rule interface, document the same default `List<Window> windows(CaptureInput input)` function and the successful-human-review transaction described above. Input and Window record signatures stay unchanged. No network command, role or public record field is added. Existing implementations remain source-compatible because the method is default.

## Required checks before merge

- A known constant background and 30 valid collection seconds produce the hand-calculated difference.
- A background inside the plume remains a measured contrast; inverse prediction subtracts the modelled background too.
- With constant location and fully settled steady concentration, the predicted contrast is zero, not the full plume value.
- A flight into a plume retains line, chamber and sensor memory in both averaging intervals.
- Missing background, flow or history cannot become zero or accepted evidence.
- SC2 rejects the SO2 branch independently of CO2.
- No usable capture windows exist before human review; automation cannot review.
- Rejected and limited decisions retain diagnostics but do not produce usable windows.
- Same command ID is idempotent; stale revision or rejected review saves no windows.
- Full real-backend SC1–SC3 outcomes are measured after the change; expected outcomes are not forced by tuning.

## Separate unresolved research decision

The current NOT_NEEDED pair rule is retained for compatibility in this amendment. Text now explains that optional mixtures may also fit. Changing pair survival semantics requires a separate owner decision because nested pairs with a zero contribution can conflict with the contract's SC1 single-source ending. Coverage evaluation must distinguish raw cutoff compatibility from the final list until that decision.
