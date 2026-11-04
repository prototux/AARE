# Decisions

Design decisions that aren't obvious from the code, newest last. Each says what was chosen and why.

## Projection margins: explicit UiConfig margins win (second audit)

When the head unit sends `UiConfig.margins`, they replace `width_margin`/`height_margin` instead of
adding to them, as the official phone does: head units keep sending the old fields for older
phones, so adding both would count the margins twice. Without explicit margins, `width_margin` and
`height_margin` are split evenly around the frame. See `car/ProjectionLayout.kt`. Approved by the
owner.

## Navigation focus: asked once per route (second audit)

The phone asks the car for projected navigation focus once when a route starts (a hosted app's trip
or a phone app's guidance), not at every guidance update. A driver who switches to the car's own
navigation mid-route isn't pulled back at the next turn; the next route asks again. See
`CarSession.guidanceChanged`.

## Exit to car (issue #46)

The car launcher's last entry, "Exit to car", and a long press on the car's HOME key send a
`VIDEO_FOCUS_REQUEST` for native focus (`VideoChannel.requestNativeFocus`). The phone doesn't drop
focus itself: it waits for the head unit's `VIDEO_FOCUS_NOTIFICATION`, so a head unit that refuses
leaves projection running and in sync. While the car shows its own UI the encoder is suspended but
the session stays up; the head unit's projection icon brings the screen back. The entry exists only
in a car session (not the on-phone preview), sits outside the user's app order, and the assistant's
"open app" tool doesn't match it.
