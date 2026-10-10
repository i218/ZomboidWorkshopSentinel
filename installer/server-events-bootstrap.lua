function isServer()return true end
serverCalls=0
WorkshopSentinelBridge={onTick=function()serverCalls=serverCalls+1 end}
serverEvents={started={},paused={}}
Events={OnServerStarted={Add=function(fn)table.insert(serverEvents.started,fn)end},OnTickEvenPaused={Add=function(fn)table.insert(serverEvents.paused,fn)end}}
