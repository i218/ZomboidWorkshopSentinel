using System;
using System.IO;
using System.Text;
using System.Linq;

class SetupTests {
    static void Check(bool ok,string message) { if(!ok) throw new Exception(message); }
    static void MustFail(Action action) { bool failed=false; try {action();} catch {failed=true;} Check(failed,"Expected rejection"); }
    public static int Main(string[] args) {
        string root=Path.Combine(args[0],"setup-tests-"+Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(root);
        string dir=Path.Combine(root,"data space");
        Directory.CreateDirectory(Path.Combine(dir,"Server")); Directory.CreateDirectory(Path.Combine(dir,"mods"));
        string ini=Path.Combine(dir,"Server","servertest1.ini"),profile=Path.Combine(dir,"mods","default.txt");
        string original="# keep this comment\r\nMods=\\Other;\\ZombieBuddy\r\nWorkshopItems=3619862853;12345\r\nPublicName=Тест\r\n";
        string mods="VERSION = 1,\r\nmods\r\n{\r\n    mod = Other,\r\n}\r\nmaps\r\n{\r\n    map = CustomMap,\r\n}\r\n";
        File.WriteAllText(ini,original,new UTF8Encoding(false)); File.WriteAllText(profile,mods,new UTF8Encoding(false));
        Setup.Install(dir,"servertest1",true,true);
        string installed=File.ReadAllText(ini),enabled=File.ReadAllText(profile);
        Check(installed==original.Replace("Mods=\\Other;\\ZombieBuddy","Mods=\\Other;\\ZombieBuddy;\\WorkshopSentinel"),"Preserve other server settings, IDs and slash syntax");
        Check(enabled.Contains("mod = Other") && enabled.Contains("mod = WorkshopSentinel") && enabled.Contains("map = CustomMap"),"Preserve client mods and map order");
        Check(Setup.FileErrorHint(new UnauthorizedAccessException("denied")).Contains("права"),"Access-denied diagnostic explains permissions");
        Check(Setup.FileErrorHint(new IOException("locked")).Contains("заблокированы"),"I/O diagnostic explains file locks");
        string jar=Path.Combine(dir,"mods","WorkshopSentinel","42","media","java","WorkshopSentinel.jar");
        Check(File.Exists(jar) && new FileInfo(jar).Length>1000,"Installer contains and deploys real JAR");
        if(File.Exists(jar+".zbs")) Check(File.ReadAllText(jar+".zbs").Contains("SteamID64:76561199013754121"),"Signature installed next to JAR");
        Check(!File.Exists(Path.Combine(dir,"mods","WorkshopSentinel","signing.local.properties")) && !Directory.GetFiles(Path.Combine(dir,"mods","WorkshopSentinel"),"*.der",SearchOption.AllDirectories).Any(),"Private signing files excluded from installer");
        string cfg=Path.Combine(dir,"WorkshopSentinel","servertest1","WorkshopSentinel.properties");
        Check(File.ReadAllText(cfg).Contains("dryRun=true") && File.ReadAllText(cfg).Contains("shutdownEnabled=false"),"Safe new config");
        File.WriteAllText(cfg,"# custom settings retained\nprovider=noop\n");
        bool unsigned=!File.Exists(jar+".zbs");
        if(unsigned) File.WriteAllText(jar+".zbs","old signature fixture");
        Setup.Install(dir,"servertest1",true,true);
        if(unsigned) {
            Check(!File.Exists(jar+".zbs"),"Unsigned update removes previous signature from loading path");
            Check(Directory.GetFiles(Path.Combine(dir,"WorkshopSentinel-installer-backups"),"obsolete-signature-*.zbs").Any(p=>File.ReadAllText(p)=="old signature fixture"),"Previous signature retained outside mod discovery");
        }
        Check(File.ReadAllText(ini)==installed && File.ReadAllText(profile)==enabled,"Idempotent activation");
        Check(File.ReadAllText(cfg)=="# custom settings retained\nprovider=noop\n","Existing config preserved");
        Check(Directory.GetFiles(Path.Combine(dir,"WorkshopSentinel-installer-backups"),"*.ini",SearchOption.AllDirectories).Any(p=>File.ReadAllText(p)==original),"Original server settings backed up");
        Check(Setup.EnableServer("Mods=Other\nWorkshopItems=123\n")=="Mods=Other;ZombieBuddy;WorkshopSentinel\nWorkshopItems=123\n","Plain mod syntax");
        MustFail(()=>Setup.EnableServer("Mods=Other\nMods=Second\n"));
        MustFail(()=>Setup.EnableServer("Mods=\\ServerAutoUpdate_B42\n"));
        MustFail(()=>Setup.EnableClient("unknown profile format"));
        MustFail(()=>Setup.EnableClient("VERSION = 1,\nmods\n{\n mod = ServerAutoUpdate_B42,\n}\n"));
        MustFail(()=>Setup.Install(dir,"missing",true,true));
        Check(File.ReadAllText(ini)==installed && File.ReadAllText(profile)==enabled,"Preflight failure does not change settings");
        string onlyClient=Path.Combine(root,"client"); Setup.Install(onlyClient,"",true,false);
        Check(File.ReadAllText(Path.Combine(onlyClient,"mods","default.txt")).Contains("WorkshopSentinel"),"New client profile created");
        Check(!Directory.Exists(Path.Combine(onlyClient,"Server")),"Client mode does not create server ini");
        string onlyServer=Path.Combine(root,"server"); Directory.CreateDirectory(Path.Combine(onlyServer,"Server"));
        File.WriteAllText(Path.Combine(onlyServer,"Server","myserver.ini"),"Mods=Other\n",new UTF8Encoding(true));
        Setup.Install(onlyServer,"myserver",false,true);
        Check(!File.Exists(Path.Combine(onlyServer,"mods","default.txt")),"Server mode leaves client profile alone");
        byte[] bom=File.ReadAllBytes(Path.Combine(onlyServer,"Server","myserver.ini"));
        Check(bom[0]==239 && bom[1]==187 && bom[2]==191,"Preserve existing UTF-8 BOM");
        string locked=Path.Combine(root,"locked-settings"); Directory.CreateDirectory(Path.Combine(locked,"Server")); Directory.CreateDirectory(Path.Combine(locked,"mods"));
        string lockedIni=Path.Combine(locked,"Server","lock.ini"),rollbackProfile=Path.Combine(locked,"mods","default.txt");
        File.WriteAllText(lockedIni,original,new UTF8Encoding(false)); File.WriteAllText(rollbackProfile,mods,new UTF8Encoding(false));
        using(var handle=new FileStream(lockedIni,FileMode.Open,FileAccess.Read,FileShare.Read)) MustFail(()=>Setup.Install(locked,"lock",true,true));
        Check(File.ReadAllText(rollbackProfile)==mods && File.ReadAllText(lockedIni)==original,"Rollback client activation when server settings locked");
        Console.WriteLine("PASS installer integration: embedded JAR, modes, activation, backups, idempotence, custom config and preflight");
        Console.WriteLine("Test files: "+root);
        return 0;
    }
}
