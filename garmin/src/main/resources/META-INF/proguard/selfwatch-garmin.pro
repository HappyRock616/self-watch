# Copyright (C) 2026 Self Watch contributors
#
# This program is free software: you can redistribute it and/or modify it under the terms of the
# GNU Affero General Public License as published by the Free Software Foundation, either version 3
# of the License, or (at your option) any later version. See the LICENSE file.

# Consumer rules for the app's R8 run (WP-01): protobuf-lite reads the fields of generated messages
# (garmin/src/main/proto → app.selfwatch.garmin.pb.proto) by reflection.
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite { <fields>; }
