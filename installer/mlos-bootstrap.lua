-- MLOS adapter preserves all installed local mods, but delegates missing Workshop validation.
function isClient()return false end
local originalRequire=require
local utils={}
function utils:getWorkshopId(info)return info.workshop end
function utils:getModsIDs(items)
    local ids,workshop={},{}
    for _,item in ipairs(items)do
        assert(item.item.modInfo,"Missing mod remains validated")
        assert(item.item.modInfo.workshop~="","Local must be handled by adapter")
        table.insert(ids,item.item.modID);table.insert(workshop,item.item.modInfo.workshop)
    end
    return ids,workshop
end
function require(name)if name=="OptionScreens/ModSelector/Refr_utils" then return utils end return originalRequire(name)end
local previousActive=getActivatedMods
function getActivatedMods()return {contains=function(self,id)return id=="ModLoadOrderSorter_b42" end}end
WorkshopSentinelTestMLOS={utils=utils,restore=function()require=originalRequire;getActivatedMods=previousActive end}
