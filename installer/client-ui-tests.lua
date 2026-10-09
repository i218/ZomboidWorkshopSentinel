local C=WorkshopSentinelClient
assert(testOptions.dict.autoPopup.value, "Automatic popup by default")
assert(not testOptions.dict.scan and not testOptions.dict.autoScan,"No manual scan or opt-in scheduling")
local testRows={{id="10",title="<IMAGE:untrusted>",version="A=2",state="NeedsUpdate",available=true,updated=2,mods={},testChangelog="<IMAGE:bad>\nA change"}}
C.open(testRows)
assert(C.window and C.window.added and #C.window.list.items==1,"Create update window")
assert(not C.window.details.text:match("<IMAGE"),"Render changelog as plain text")
for _,button in ipairs(C.window.children) do
    if button.title==expectedRussianCopy then button.onclick(button.target) end
    if button.title==expectedRussianWorkshop then button.onclick(button.target) end
end
assert(testClipboard=="https://steamcommunity.com/sharedfiles/filedetails/?id=10" and testOverlay=="10","Link actions")
C.window:close(); assert(C.window==nil,"Close window")
C.rows=testRows; C.status="cache-error"; C.changedCount=1; C.notify()
assert(C.window~=nil,"Changed results still open when cache save fails")
C.window:close()
C.notify(); assert(C.window==nil,"Do not reopen identical dismissed update")
C.rows[1].updated=3; C.notify(); assert(C.window~=nil,"Notify a newer revision")
C.window:close()
MainScreen.instance.inGame=true; C.rows[1].updated=4; C.notify()
assert(C.window==nil,"No interrupting popup during gameplay")
MainScreen.instance.inGame=false
for _,fn in ipairs(testEvents.ticks) do fn() end
assert(C.window~=nil,"Deferred update opens automatically on return to menu")
C.window:close()
for _,fn in ipairs(testEvents.menus) do fn() end
assert(MainScreen.instance.workshopSentinelButton and MainScreen.instance.workshopSentinelButton.visible,"Main-menu button")
assert(MainScreen.instance.workshopSentinelButton.title==expectedRussianMenu,"Russian text survives independently checked Unicode")
C.open(); C.window:prerender()
assert(C.window.drawn[1]=="WorkshopSentinel - "..expectedRussianMenu,"Russian window heading")
C.window:close()
testLanguage="EN"; C.open(); C.window:prerender()
assert(C.window.drawn[1]=="WorkshopSentinel - Mod updates","English heading")
assert(C.window.children[3].title=="Open Workshop","Only informational actions")
C.window:close(); testLanguage="RU"
local count=#MainScreen.instance.children
for _,fn in ipairs(testEvents.menus) do fn() end
assert(#MainScreen.instance.children==count,"No duplicate menu button")
MainScreen.instance.bottomPanel.visible=false
for _,fn in ipairs(testEvents.ticks) do fn() end
assert(not MainScreen.instance.workshopSentinelButton.visible,"Hide on another menu screen")
MainScreen.instance.bottomPanel.visible=true
for _,fn in ipairs(testEvents.ticks) do fn() end
assert(MainScreen.instance.workshopSentinelButton.visible,"Restore main-menu button")
C.open(testRows)
for _,fn in ipairs(testEvents.games) do fn() end
assert(C.window==nil and not MainScreen.instance.workshopSentinelButton.visible,"Close menu UI on game start")
local requests=#testRequests
MainScreen.instance.workshopSentinelButton.onclick()
assert(C.window and #testRequests==requests,"Viewing results never requests a scan")
assert(#C.window.children==5,"Two content panels and three buttons; no Check button")
