-- Minimal UI doubles; verifies integration flow, not pixels or native input.
function require(name) return true end
Translator = { getLanguage = function() return { name = function() return "RU" end } end }
testLanguage = "RU"
function getText(key)
    assert(testTranslations.EN[key], "Missing English translation: " .. key)
    assert(testTranslations.RU[key], "Missing Russian translation: " .. key)
    return testTranslations[testLanguage][key]
end
function getCore() return { getScreenWidth = function() return 1280 end, getScreenHeight = function() return 720 end } end
UIFont = { Small = 1, Medium = 2 }
local Widget = {}
Widget.__index = Widget
function Widget:derive(name) local cls = { Type = name }; cls.__index = cls; setmetatable(cls, {__index=self}); return cls end
function Widget:new(x,y,w,h,title,target,onclick)
    return setmetatable({x=x,y=y,width=w,height=h,title=title,target=target,onclick=onclick,children={},items={}},self)
end
function Widget:initialise() end
function Widget:addChild(child) table.insert(self.children,child) end
function Widget:addScrollBars() end
function Widget:setText(text) self.text=text end
function Widget:paginate() end
function Widget:prerender() end
function Widget:drawText(text,...) self.drawn=self.drawn or {}; table.insert(self.drawn,text) end
function Widget:drawRect(...) end
function Widget:drawRectBorder(...) end
function Widget:addToUIManager() self.added=true end
function Widget:removeFromUIManager() self.added=false end
function Widget:setOnMouseDownFunction(target,fn) self.selectionTarget=target; self.select=fn end
function Widget:clear() self.items={} end
function Widget:addItem(name,row) table.insert(self.items,{text=name,item=row,index=#self.items+1}) end
function Widget:setAnchorLeft(value) end
function Widget:setAnchorRight(value) end
function Widget:setVisible(value) self.visible=value end
ISPanel=Widget:derive("ISPanel"); ISButton=Widget:derive("ISButton")
ISScrollingListBox=Widget:derive("ISScrollingListBox"); ISRichTextPanel=Widget:derive("ISRichTextPanel")
PZAPI={ModOptions={}}
function PZAPI.ModOptions:create(id,name)
    local options={dict={}}
    function options:addTickBox(id,name,value)
        self.dict[id]={value=value,getValue=function(self)return self.value end}
    end
    function options:getOption(id) return self.dict[id] end
    function options:addButton(id,name,tooltip,fn) self.dict[id]={onclick=fn} end
    testOptions=options; return options
end
MainScreen={instance=Widget:new(0,0,1280,720)}
MainScreen.instance.bottomPanel={visible=true,getIsVisible=function(self)return self.visible end}
Clipboard={setClipboard=function(text)testClipboard=text end}
function activateSteamOverlayToWorkshopItem(id) testOverlay=id end
