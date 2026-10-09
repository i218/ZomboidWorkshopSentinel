-- Loaded only by the dedicated server. Java API is registered by Main.main.
if not isServer() then return end
print('[WorkshopSentinel server] Lua bootstrap v0.4.8 loaded; waiting for ZombieBuddy Java bridge')
local reportedMissing = false
local failed = false
local function tick()
    if not WorkshopSentinelBridge then
        if not reportedMissing then
            print('[WorkshopSentinel] Java bridge missing: Lua loaded, but Java initialization is unconfirmed. Check [ZB] java mod list for io.workshopsentinel and Main.main errors; confirm -javaagent on this dedicated-server JVM and remove legacy .WorkshopSentinel-install-* duplicates. No restart will be requested.')
            reportedMissing = true
        end
        return
    end
    if failed then return end
    local ok, err = pcall(WorkshopSentinelBridge.onTick)
    if not ok then
        failed = true
        print('[WorkshopSentinel] Bridge failed; monitoring disabled until JVM restart: ' .. tostring(err))
    end
end
Events.OnTick.Add(tick)
