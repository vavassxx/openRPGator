-- Transparent bridge for Grindlands custom UI.
-- The authoritative server owns all widget contents; this client script only forwards them
-- through the sandbox so custom buttons remain interactive.
ui.on_layout(function(widgets, strings)
    ui.layout(widgets, strings)
end)

ui.on_command(function(code, arg)
    ui.send(code, arg)
end)
