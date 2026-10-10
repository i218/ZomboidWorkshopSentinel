assert(#serverEvents.started==1 and #serverEvents.paused==1,"Register startup and paused simulation callbacks")
assert(serverCalls==0,"No premature service calls during Lua loading")
serverEvents.started[1]();assert(serverCalls==1,"Exclude client requirement before ordinary ticks")
serverEvents.paused[1]();assert(serverCalls==2,"PauseEmpty does not stop bridge maintenance")
