if isServer() then return end
require "WorkshopSentinelClientModel"
require "ISUI/ISPanel"
require "ISUI/ISButton"
require "ISUI/ISScrollingListBox"
require "ISUI/ISRichTextPanel"
require "PZAPI/ModOptions"
local C = WorkshopSentinelClient
local function plain(text)
    return tostring(text or ""):gsub("<", "["):gsub(">", "]"):gsub("\r", ""):gsub("\n", " <LINE> ")
end
local function updatedDate(stamp)
    if stamp and os and os.date then
        local ok, value = pcall(os.date, "%d.%m.%Y %H:%M", stamp)
        if ok then return value end
    end
    return tostring(stamp or "")
end
local statusText = {
    checking = "UI_WorkshopSentinel_CheckingWorkshop",
    complete = "UI_WorkshopSentinel_CheckcompleteChangesarecomparedwiththeprevioussession",
    empty = "UI_WorkshopSentinel_NoactiveWorkshopmodsLocalmodsareskipped",
    ["no-steam"] = "UI_WorkshopSentinel_Steamisdisabled",
    cooldown = "UI_WorkshopSentinel_Pleasewait60secondsbetweenchecks",
    timeout = "UI_WorkshopSentinel_SteamrequesttimedoutPreviousresultsareretained",
    ["steam-error"] = "UI_WorkshopSentinel_SteamreturnedanerrororincompleteresultCachewasnotchanged",
    ["api-error"] = "UI_WorkshopSentinel_ClientAPIerrorSeethegamelog",
    ["cache-error"] = "UI_WorkshopSentinel_ResultsarereadycachecouldnotbesavedSeethegamelog",
    reset = "UI_WorkshopSentinel_CacheclearedNextscancreatesanewbaseline"
}
local options = PZAPI.ModOptions:create("WorkshopSentinel", "WorkshopSentinel")
print("[WorkshopSentinel client] UI loaded v0.4.9; Mod Options registered")
options:addTickBox("autoPopup", getText("UI_WorkshopSentinel_Showwindowwhenchangesarefound"), true)
options:addTickBox("changedOnly", getText("UI_WorkshopSentinel_Showchangedmodsonly"), true)
local function enabled(id) return options:getOption(id):getValue() end
local Window = ISPanel:derive("WorkshopSentinelWindow")
function Window:initialise()
    ISPanel.initialise(self)
    self.moveWithMouse = true
    self.list = ISScrollingListBox:new(12, 60, math.floor(self.width * 0.40), self.height - 126)
    self.list:initialise(); self.list.itemheight = 44
    self.list:setOnMouseDownFunction(self, Window.selectRow)
    self.list.doDrawItem = function(list, y, item, alt)
        list:drawRectBorder(0, y, list.width, list.itemheight, 0.3, 0.7, 0.7, 0.7)
        if list.selected == item.index then list:drawRect(0, y, list.width, list.itemheight, 0.25, 0.4, 0.6, 0.8) end
        local r = item.item
        local label = r.title or r.id
        list:drawText(label, 8, y + 3, 1, 1, 1, 1, UIFont.Small)
        local state = r.available and getText("UI_WorkshopSentinel_Updateavailable")
            or r.changed and getText("UI_WorkshopSentinel_Changedsinceprevioussession") or r.state or ""
        list:drawText(state, 8, y + 22, 0.75, 0.85, 1, 1, UIFont.Small)
        return y + list.itemheight
    end
    self:addChild(self.list)
    local x = self.list.x + self.list.width + 12
    self.details = ISRichTextPanel:new(x, 60, self.width - x - 12, self.height - 126)
    self.details:initialise(); self.details:addScrollBars(); self:addChild(self.details)
    local function button(text, bx, width, callback)
        local b = ISButton:new(bx, self.height - 48, width, 30, text, self, callback)
        b:initialise(); self:addChild(b); return b
    end
    local bw = math.floor((self.width - 48) / 3)
    button(getText("UI_WorkshopSentinel_OpenWorkshop"), 12, bw, function(w)
        if w.selectedRow then activateSteamOverlayToWorkshopItem(w.selectedRow.id) end
    end)
    button(getText("UI_WorkshopSentinel_Copylink"), 24 + bw, bw, function(w)
        if w.selectedRow then Clipboard.setClipboard("https://steamcommunity.com/sharedfiles/filedetails/?id=" .. w.selectedRow.id) end
    end)
    button(getText("UI_WorkshopSentinel_Close"), 36 + 2 * bw, bw, Window.close)
    self:refresh()
end
function Window:close()
    self:removeFromUIManager()
    if C.window == self then C.window = nil end
end
function Window:prerender()
    ISPanel.prerender(self)
    self:drawText("WorkshopSentinel - " .. getText("UI_WorkshopSentinel_Modupdates"), 12, 10, 1, 1, 1, 1, UIFont.Medium)
    local label = statusText[C.status] or "UI_WorkshopSentinel_AutomaticChecks"
    self:drawText(getText(label), 12, 36, 0.8, 0.85, 0.9, 1, UIFont.Small)
end
function Window:refresh()
    local selectedId = self.selectedRow and self.selectedRow.id
    self.list:clear(); self.selectedRow = nil
    for _, row in ipairs(self.testRows or C.rows) do
        if self.testRows or not enabled("changedOnly") or row.available or row.changed then self.list:addItem(row.title, row) end
    end
    self.details:setText(getText(#self.list.items == 0 and "UI_WorkshopSentinel_NoChanges" or "UI_WorkshopSentinel_SelectamodThefirstsuccessfulscancreatesabaselineitdoesnotproveamodwasrecentlyupdated"))
    self.details:paginate()
    if #self.list.items > 0 then
        local index = 1
        for i, item in ipairs(self.list.items) do if item.item.id == selectedId then index = i; break end end
        self.list.selected = index; self:selectRow(self.list.items[index].item)
    end
end
function Window:selectRow(row)
    self.selectedRow = row
    local previous = C.baseline[row.id]
    local changelog = row.testChangelog or C.changelog(row)
    local text = plain(row.title) .. " <LINE> Workshop ID: " .. row.id .. " <LINE> " .. plain(row.version)
        .. " <LINE> Steam: " .. plain(row.state) .. " <LINE> " .. getText("UI_WorkshopSentinel_Steamupdatedate") .. updatedDate(row.updated)
    if previous then text = text .. " <LINE> " .. getText("UI_WorkshopSentinel_Previoussession") .. plain(previous.version) end
    text = text .. " <LINE> <LINE> " .. (changelog ~= "" and plain(changelog) or getText("UI_WorkshopSentinel_NolocalChangeLogtxt"))
    self.details:setText(text); self.details:paginate()
end
function C.open(testRows)
    if C.window then C.window:close() end
    local width = math.min(760, getCore():getScreenWidth() - 30)
    local height = math.min(460, getCore():getScreenHeight() - 30)
    local w = Window:new((getCore():getScreenWidth() - width) / 2, (getCore():getScreenHeight() - height) / 2, width, height)
    w.testRows = testRows; w:initialise(); w:addToUIManager(); C.window = w
end
options.apply = function() if C.window then C.window:refresh() end end
table.insert(C.listeners, function()
    if C.window then C.window:refresh()
    end
end)
local function notifyChanges()
    local main = MainScreen and MainScreen.instance
    if not main or main.inGame or not main.bottomPanel or not main.bottomPanel:getIsVisible() then return end
    if C.status ~= "complete" and C.status ~= "cache-error" then return end
    local changed = {}
    for _, row in ipairs(C.rows) do
        if row.available or row.changed then table.insert(changed, row.id .. ":" .. tostring(row.updated) .. ":" .. row.version .. ":" .. tostring(row.state)) end
    end
    table.sort(changed)
    local signature = table.concat(changed, "|")
    if signature == "" then C.notifiedChanges = nil; return end
    if enabled("autoPopup") and signature ~= C.notifiedChanges then
        C.notifiedChanges = signature
        if not C.window then C.open() end
    end
end
table.insert(C.listeners, notifyChanges)
local function menu()
    local main = MainScreen and MainScreen.instance
    if not main or main.inGame then
        print("[WorkshopSentinel client] Menu button deferred: MainScreen not ready")
        return
    end
    if not main.workshopSentinelButton then
        local b = ISButton:new(main.width - 282, 35, 260, 32, getText("UI_WorkshopSentinel_Modupdates"), C, function() C.open() end)
        b:initialise(); b:setAnchorLeft(false); b:setAnchorRight(true); main:addChild(b)
        main.workshopSentinelButton = b
        print("[WorkshopSentinel client] Main-menu updates button created")
    end
    main.workshopSentinelButton:setVisible(true)
    C.enableAutomaticChecks()
end
Events.OnMainMenuEnter.Add(menu)
Events.OnPreUIDraw.Add(function()
    notifyChanges()
    local main = MainScreen and MainScreen.instance
    if main and main.workshopSentinelButton then
        main.workshopSentinelButton:setVisible(not main.inGame and main.bottomPanel and main.bottomPanel:getIsVisible() or false)
    end
end)
Events.OnGameStart.Add(function()
    if C.window then C.window:close() end
    local main = MainScreen and MainScreen.instance
    if main and main.workshopSentinelButton then main.workshopSentinelButton:setVisible(false) end
end)
