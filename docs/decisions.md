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

## Deferred from the second audit (owner to decide or check)

- **Setup card and optional permissions (finding 46).** The "Finish setting up" card still waits for
  every runtime permission, the optional ones too (calendar, call log, contacts, phone calls). A user
  who refuses one of those for good keeps the card. Splitting required from optional permissions
  changes what counts as "set up", so it is left to the owner.
- **Translations (findings 50 and 66).** The car-screen and auto-reply texts are string resources now,
  but the new keys (`msg_autoreply_default`, `dash_menu_exit_to_car`, `media_error_*`, `tpl_*`,
  `carapp_error_*`, `common_ok`/`common_try_again`/`common_something_went_wrong`…) are English only
  in all 19 locales. Status: made translatable, translations pending. `msg_autoreply_default` matters
  most, as it is sent to contacts.
- **Rotary focus with the phone locked on Android 14+ (finding 6).** Leaving touch mode with
  `requestFocusFromTouch()` may be refused when the phone's own focused window isn't ours. To check
  on a phone: logcat for "Touch mode switch rejected" with the screen locked and a knob-only unit.
  If it is refused, the car UI needs focus targets that work in touch mode.
