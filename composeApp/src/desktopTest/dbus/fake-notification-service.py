#!/usr/bin/env python3
"""A stand-in org.freedesktop.Notifications service for GioNotificationBusTest.

Run it on a private session bus (dbus-run-session), never the desktop's own. It answers
GetCapabilities, records each Notify call on stderr, answers it with id 7, and half a second later
reports a click on it (ActionInvoked 7 "default"), as a notification service does when the user
clicks a notification.
"""
import sys

import gi

gi.require_version("Gio", "2.0")
from gi.repository import Gio, GLib  # noqa: E402

XML = """
<node>
  <interface name="org.freedesktop.Notifications">
    <method name="GetCapabilities"><arg type="as" direction="out"/></method>
    <method name="Notify">
      <arg type="s" direction="in"/><arg type="u" direction="in"/><arg type="s" direction="in"/>
      <arg type="s" direction="in"/><arg type="s" direction="in"/><arg type="as" direction="in"/>
      <arg type="a{sv}" direction="in"/><arg type="i" direction="in"/>
      <arg type="u" direction="out"/>
    </method>
    <signal name="ActionInvoked"><arg type="u"/><arg type="s"/></signal>
  </interface>
</node>
"""
PATH = "/org/freedesktop/Notifications"
NAME = "org.freedesktop.Notifications"


def on_call(connection, sender, path, interface, method, params, invocation):
    if method == "GetCapabilities":
        invocation.return_value(GLib.Variant("(as)", (["body", "actions"],)))
        return
    print("Notify", params.unpack(), file=sys.stderr, flush=True)
    invocation.return_value(GLib.Variant("(u)", (7,)))

    def click():
        connection.emit_signal(None, PATH, NAME, "ActionInvoked", GLib.Variant("(us)", (7, "default")))
        return False

    GLib.timeout_add(500, click)


def on_bus(connection, name):
    node = Gio.DBusNodeInfo.new_for_xml(XML)
    connection.register_object(PATH, node.interfaces[0], on_call, None, None)


Gio.bus_own_name(Gio.BusType.SESSION, NAME, Gio.BusNameOwnerFlags.NONE, on_bus, None, None)
GLib.MainLoop().run()
