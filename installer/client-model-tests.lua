local C = WorkshopSentinelClient
assert(C.cacheFile:match("%.txt$"), "Use a B42 allowed extension")
assert(getFileWriter("unsupported.tsv",true,false)==nil,"Fixture mirrors unsupported extension")
testWriterUnavailable=true
local saved, reason=C.saveCache({})
assert(saved==false and reason:match("Cache writer unavailable"),"Nil writer is a non-throwing failure")
testWriterUnavailable=false


testActive = {"A", "B", "Local"}; testMods = { A = info("A", "10", "1"), B = info("B", "10", "1"), Local = info("Local", "", "1") }
assert(#C.collect() == 1 and #C.localRows == 1, "Separate Workshop groups and local mods")
assert(C.start()); C.tick()
local request = testRequests[#testRequests]
request.callback(request.context, "Completed", list({detail("10", 100)}))
testTime = testTime + 4000; C.tick()
assert(C.status == "complete" and C.changedCount == 0 and C.rows[1].first and #C.rows == 2, "First scan is baseline only")
assert(testFiles[C.cacheFile]:match("10\t100"), "Persist cache")
C.loadCache(); testTime = testTime + 60000
assert(C.start()); C.tick(); request = testRequests[#testRequests]
request.callback(request.context, "Completed", list({detail("10", 200, "NeedsUpdate")}))
testTime = testTime + 4000; C.tick()
assert(C.changedCount == 1 and C.rows[1].available and C.rows[1].changed, "Remote changes and Steam NeedsUpdate")
assert(C.baseline["10"].updated == 100, "Keep previous session baseline")
assert(not C.start(), "Scan cooldown")
C.loadCache(); testMods.A = info("A", "10", "2"); testTime = testTime + 60000
assert(C.start()); C.tick(); request = testRequests[#testRequests]
request.callback(request.context, "Completed", list({detail("10", 200)}))
testTime = testTime + 4000; C.tick()
assert(C.rows[1].changed and not C.rows[1].available, "Installed metadata version changed without remote timestamp change")
local goodCache = testFiles[C.cacheFile]
testTime = testTime + 60000; C.start(); C.tick(); request = testRequests[#testRequests]
request.callback(request.context, "Completed", list({}))
assert(C.status == "steam-error" and testFiles[C.cacheFile] == goodCache, "Partial result cannot commit cache")

-- Bad records must not raise error(): Kahlua logs caught exceptions as stacks.
local previousRows, previousBaseline = C.rows, C.baseline
local originalError, validationThrows = error, 0
local function badResponse(results, expectedReason, expectedId)
    testTime=testTime+60000; assert(C.start()); C.tick()
    local pendingRequest=testRequests[#testRequests]
    error=function(message) validationThrows=validationThrows+1; return originalError(message) end
    pendingRequest.callback(pendingRequest.context,"Completed",results)
    error=originalError
    assert(C.status=="steam-error" and not C.busy,"Reject invalid Steam batch")
    assert(C.rows==previousRows and C.baseline==previousBaseline and testFiles[C.cacheFile]==goodCache,
        "Bad response preserves published results, baseline and disk cache")
    local message=C.failures["Steam response"].message
    assert(message:match(expectedReason) and message:match("id="..expectedId),"Diagnostic identifies reason and item")
    return message
end
assert(badResponse(list({detail("10",0)}),"invalid update timestamp","10"):match("mods=A,B"),
    "Diagnostic identifies installed mods associated with the Workshop item")
local messages=#testMessages
badResponse(list({detail("10",0)}),"invalid update timestamp","10")
assert(#testMessages==messages,"Repeated identical Steam failure is bounded")
badResponse(list({detail("10",-1)}),"invalid update timestamp","10")
badResponse(list({detail("10","invalid")}),"invalid update timestamp","10")
badResponse(list({detail("10",math.huge)}),"invalid update timestamp","10")
badResponse(list({detail("10",100.5)}),"invalid update timestamp","10")
badResponse(list({detail("999",100)}),"unexpected Workshop ID","999")
badResponse(list({detail("10",100),detail("10",100)}),"duplicate Workshop ID","10")
badResponse(list({}),"missing requested item","10")
assert(validationThrows==0,"Invalid responses never throw a Lua exception")
testTime=testTime+60000; assert(C.start()); C.tick(); request=testRequests[#testRequests]
request.callback(request.context,"Completed",nil)
assert(C.status=="steam-error" and C.failures["Steam response"].message:match("missing detail list"),
    "Nil response is handled without a native method call")
testTime=testTime+60000; assert(C.start()); C.tick(); request=testRequests[#testRequests]
request.callback(request.context,"Completed",list({detail("10",200)}))
testTime=testTime+4000; C.tick()
assert(C.status=="complete" and not C.failures["Steam response"],"Successful retry reports recovery")
testTime = testTime + 60000; C.start(); C.tick(); request = testRequests[#testRequests]
testTime = testTime + 30001; C.tick()
assert(C.status == "timeout" and not C.busy, "Timeout")
request.callback(request.context, "Completed", list({detail("10", 999)}))
assert(C.status == "timeout" and testFiles[C.cacheFile] == goodCache, "Ignore late callback")
testTime = testTime + 60000; testSteam = false
assert(C.start() and C.status == "local-only" and #C.rows == 1); testSteam = true
testFiles["A/ChangeLog.txt"] = "Version 2\nA change"
assert(C.changelog({mods={"A"}}):match("A change"), "Read local ChangeLog")
testActive, testMods = {}, {}
for i=1,201 do local id = tostring(1000+i); testActive[i] = "Mod" .. id; testMods[testActive[i]] = info(testActive[i], id, "1") end
testTime = testTime + 60000; assert(C.start()); C.tick()
local first = #testRequests; request = testRequests[first]
assert(request.ids:size() == 100, "Max batch size 100")
local results = {}; for _,id in ipairs(request.ids.values) do table.insert(results, detail(id, 300)) end
request.callback(request.context, "Completed", list(results))
C.tick(); assert(#testRequests == first, "Wait between batches")
testTime = testTime + 4000; C.tick(); request = testRequests[#testRequests]
assert(request.ids:size() == 100)
results = {}; for _,id in ipairs(request.ids.values) do table.insert(results, detail(id, 300)) end
request.callback(request.context, "Completed", list(results))
testTime = testTime + 4000; C.tick(); request = testRequests[#testRequests]
assert(request.ids:size() == 1)
request.callback(request.context, "Completed", list({detail(request.ids:get(0), 300)}))
testTime = testTime + 4000; C.tick()
assert(C.status == "complete" and #C.rows == 201, "All batches complete")
local completeRows, completeCache, completeBaseline=C.rows,testFiles[C.cacheFile],C.baseline
testTime=testTime+60000; assert(C.start()); C.tick(); request=testRequests[#testRequests]
results={}; for _,id in ipairs(request.ids.values) do table.insert(results,detail(id,400)) end
request.callback(request.context,"Completed",list(results))
testTime=testTime+4000; C.tick(); request=testRequests[#testRequests]
request.callback(request.context,"Completed",list({detail(request.ids:get(0),0)}))
assert(C.status=="steam-error" and C.rows==completeRows and C.baseline==completeBaseline
    and testFiles[C.cacheFile]==completeCache,"Second-batch failure cannot publish first-batch partial changes")
assert(C.resetCache() and testFiles[C.cacheFile] == "", "Cache reset")
assert(C.saveCache({["10"]={updated=1790000000,version="A=2"}})); C.loadCache()
assert(C.baseline["10"].updated == 1790000000, "Unix timestamp cache roundtrip")
testTime=testTime+60000; testWriterUnavailable=true
assert(C.start()); C.tick(); request=testRequests[#testRequests]
local pending={}; for _,id in ipairs(request.ids.values) do table.insert(pending,detail(id,1790000001,"NeedsUpdate")) end
request.callback(request.context,"Completed",list(pending))
testTime=testTime+4000; C.tick()
while C.busy do
    request=testRequests[#testRequests]
    pending={}; for _,id in ipairs(request.ids.values) do table.insert(pending,detail(id,1790000001,"NeedsUpdate")) end
    if C.pending then request.callback(request.context,"Completed",list(pending)) end
    testTime=testTime+4000; C.tick()
end
assert(C.status=="cache-error" and #C.rows==201 and C.changedCount==201,"Keep scan results when persistence unavailable")
testWriterUnavailable=false

-- Automatic scheduling needs no user action and does not flood empty/error states.
testActive={"A"}; testMods={A=info("A","10","2")}
MainScreen={instance={inGame=false}}
testTime=testTime+60000; C.enableAutomaticChecks()
local count=#testRequests; C.tick()
assert(#testRequests==count+1,"Automatic first menu scan")
local request=testRequests[#testRequests]
request.callback(request.context,"Completed",list({detail("10",1790000002)}))
testTime=testTime+4000; C.tick()
local due=C.nextAutoAt; testTime=due-1; C.tick()
assert(#testRequests==count+1,"No scan before twenty minutes")
MainScreen.instance.inGame=true; testTime=due; C.tick()
assert(#testRequests==count+1,"No new background scan in gameplay")
MainScreen.instance.inGame=false; C.tick()
assert(#testRequests==count+2,"Resume due scan on return to menu")
request=testRequests[#testRequests]
request.callback(request.context,"Completed",list({detail("10",1790000002)}))
testTime=testTime+4000; C.tick()
testActive={}; testTime=C.nextAutoAt; C.tick()
assert(C.status=="empty" and C.nextAutoAt==testTime+C.autoIntervalMs,"Empty scan schedules next retry")
local nextDue=C.nextAutoAt; testTime=testTime+1; C.tick()
assert(C.nextAutoAt==nextDue,"No per-frame empty retries")
testActive={"A"}; testTime=C.nextAutoAt; C.tick()
count=#testRequests; testTime=testTime+30001; C.tick()
assert(C.status=="timeout","Automatic request timeout")
C.tick(); assert(#testRequests==count,"Automatic failure does not retry every frame")
testTime=C.nextAutoAt; C.tick()
assert(#testRequests==count+1,"Automatic retry at next interval")
C.fail("timeout")
C.automatic=false; MainScreen=nil

-- Local mods are tracked automatically without any Steam query, including offline.
testActive={"Local","\\Local","Broken","Missing"}
testMods={Local=info("Local","","1"),Broken={getWorkshopID=function()error("AccessDeniedException: metadata")end}}
testSteam=false; testTime=testTime+60000
local requests=#testRequests
assert(C.start() and #C.rows==1 and C.status=="local-only","Local scan isolates missing/unreadable mods and deduplicates IDs")
assert(#testRequests==requests and not C.rows[1].available,"Never query Steam for a local mod")
C.loadCache(); assert(C.baseline["local:Local"].version=="Local=1","Local baseline survives cache roundtrip")
testMods.Local=info("Local","","2"); testTime=testTime+60000
assert(C.start() and C.rows[1].changed and C.changedCount==1,"Local mod version change is detected offline")
assert(C.baseline["10"],"Offline local scan preserves existing Steam baseline")
local oldBaseline=C.baseline
local originalReader=getFileReader
getFileReader=function()error("AccessDeniedException: cache")end
C.loadCache(); assert(C.baseline==oldBaseline,"Failed cache read retains memory baseline")
getFileReader=originalReader
testWriterUnavailable=true; testTime=testTime+60000
assert(C.start() and C.status=="cache-error" and #C.rows==1,"Denied cache writer retains local results")
testWriterUnavailable=false; testSteam=true
