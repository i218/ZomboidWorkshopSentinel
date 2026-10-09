for _,fn in ipairs(testEvents.menus)do fn()end
local u=WorkshopSentinelTestMLOS.utils
local a={item={modID="\\OtherLocal",modInfo={workshop=""}}}
local b={item={modID="WorkshopMod",modInfo={workshop="123"}}}
local c={item={modID="WorkshopSentinel",modInfo={workshop=""}}}
local mods,ids=u:getModsIDs({a,b,c})
assert(table.concat(mods,";")=="\\OtherLocal;WorkshopMod;WorkshopSentinel","Preserve local mod IDs and exact order")
assert(#ids==1 and ids[1]=="123","Only real Workshop IDs")
local ok=pcall(u.getModsIDs,u,{a,{item={modID="Missing"}}})
assert(not ok,"Do not silently retain genuinely missing mods")
local wrapped=u.getModsIDs
for _,fn in ipairs(testEvents.menus)do fn()end
assert(u.getModsIDs==wrapped,"MLOS adapter installation is idempotent")
WorkshopSentinelTestMLOS.restore()
