The `aap_protobuf` definitions come from aasdk (https://github.com/opencardev/aasdk, `protobuf/`),
Copyright (C) 2018 f1x.studio (Michal Szwaj) and the OpenCarDev community, GPL-3.0-or-later.
Local changes are marked with `// AARE:` comments, except two made across all the files:

- **No field is `required`.** Every field aasdk marks `required` is `optional` here (the wire format
  is the same). Some head units encode like proto3 and leave out every field whose value is 0:
  Ford SYNC 4 sends AUTH_COMPLETE with an empty body and a service discovery response that the
  `required` fields made unparsable (#74). A missing field reads as 0, which is what such a head
  unit meant.
- For the same reason, enum fields whose enum has a 0 value that isn't listed first (proto2's
  default is the first value) get `[default = …]` pointing at the 0 value, e.g.
  `ChannelOpenResponse.status [default = STATUS_SUCCESS]`.
