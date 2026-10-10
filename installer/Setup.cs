using System;
using System.IO;
using System.IO.Compression;
using System.Reflection;
using System.Windows.Forms;
using System.Drawing;
using System.Collections.Generic;
using System.Text;
using System.Text.RegularExpressions;
using System.Security.Cryptography;

[assembly: AssemblyTitle("WorkshopSentinel Setup")]
[assembly: AssemblyVersion("0.4.12.0")]
[assembly: AssemblyFileVersion("0.4.12.0")]

class Setup {
    static string DefaultDir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Zomboid");
    static string Operation = "Запуск", CurrentPath = "";
    internal static string FileErrorHint(Exception e) {
        if(e is UnauthorizedAccessException || e is System.Security.SecurityException)
            return "Доступ запрещён. Проверьте права учётной записи на указанный путь, атрибут Только чтение и блокировку файла. Установщик не изменяет права доступа автоматически.";
        if(e is IOException)
            return "Проверьте, что путь имеет правильный тип (файл или папка), доступен диск и файлы не заблокированы запущенной игрой или сервером. После устранения причины повторите установку.";
        return "";
    }
    static string ErrorDetails(Exception e) {
        string report="Операция: "+Operation+"\nПуть: "+CurrentPath+"\n\n"+e.Message+"\n"+FileErrorHint(e);
        try {
            string log=Path.Combine(Path.GetTempPath(),"WorkshopSentinel-setup-error-"+DateTime.UtcNow.ToString("yyyyMMdd-HHmmss")+".txt");
            File.WriteAllText(log,report+"\n\n"+e.ToString());
            report+="\n\nОтчёт: "+log;
        } catch { }
        return report;
    }
    static void At(string operation,string path) { Operation=operation; CurrentPath=path; }
    [STAThread] static int Main(string[] args) {
        try {
            if (args.Length > 0) {
                var options = new Dictionary<string,string>();
                for (int i=0;i<args.Length;i++) {
                    if (args[i]=="--silent") continue;
                    if (i+1>=args.Length) throw new Exception("Missing option value.");
                    options.Add(args[i],args[++i]);
                }
                foreach(string key in options.Keys) if(key!="--data-dir" && key!="--server-name" && key!="--mode") throw new Exception("Unknown option: "+key);
                string mode=Get(options,"--mode","both");
                if(mode!="both" && mode!="client" && mode!="server") throw new Exception("Unknown mode: "+mode);
                Install(Get(options,"--data-dir",DefaultDir),Get(options,"--server-name","servertest"),mode!="server",mode!="client");
                return 0;
            }
            Application.EnableVisualStyles();
            Application.Run(new SetupForm());
            return 0;
        } catch (Exception e) {
            string details=ErrorDetails(e);
            if (Array.IndexOf(args,"--silent")<0) MessageBox.Show(details,"WorkshopSentinel: ошибка",MessageBoxButtons.OK,MessageBoxIcon.Error);
            return 1;
        }
    }
    static string Get(Dictionary<string,string> d,string k,string fallback) { return d.ContainsKey(k)?d[k]:fallback; }
    static string ValidateDir(string dir) {
        if (String.IsNullOrWhiteSpace(dir) || !Path.IsPathRooted(dir)) throw new Exception("Укажите полный путь к папке данных Zomboid.");
        string full=Path.GetFullPath(dir).TrimEnd(Path.DirectorySeparatorChar,Path.AltDirectorySeparatorChar);
        if (full.Length<=3) throw new Exception("Нельзя использовать корень диска.");
        return full;
    }
    public static void Install(string dataDir,string name,bool client,bool server) {
            dataDir=ValidateDir(dataDir);
            if (!client && !server) throw new Exception("Выберите клиент или сервер.");
            if (server && (String.IsNullOrWhiteSpace(name) || !Regex.IsMatch(name,@"^[A-Za-z0-9_.-]+$") || name=="." || name=="..")) throw new Exception("Недопустимое имя сервера.");
        CheckOwned(dataDir);
        if(client) CheckOwned(dataDir,"WorkshopSentinelClient");
        string ini=Path.Combine(dataDir,"Server",name+".ini");
        string profile=Path.Combine(dataDir,"mods","default.txt");
        // Validate all selected settings before replacing any mod files.
        string serverText=null,clientText=null;
        if(server) {
            At("Проверка настроек выбранного сервера",ini);
            if(!File.Exists(ini)) throw new IOException("Настройки сервера не найдены: "+ini+". Создайте сервер в игре или выберите существующий.");
            serverText=EnableServer(File.ReadAllText(ini,Encoding.UTF8));
        }
        if(client) clientText=EnableClient(File.Exists(profile)?File.ReadAllText(profile,Encoding.UTF8):"VERSION = 1,\r\n\r\nmods\r\n{\r\n}\r\n\r\nmaps\r\n{\r\n}\r\n");
        using (Stream payload=Assembly.GetExecutingAssembly().GetManifestResourceStream("payload.zip"))
        using (var zip=new ZipArchive(payload,ZipArchiveMode.Read)) {
                ValidatePayload(zip);
                Deploy(zip,"WorkshopSentinel/",dataDir);
                if(client) Deploy(zip,"WorkshopSentinel/client-support/WorkshopSentinelClient/",dataDir);
                VerifyJar(zip,Path.Combine(dataDir,"mods","WorkshopSentinel","42","media","java","WorkshopSentinel.jar"));
                bool signed=zip.GetEntry("WorkshopSentinel/42/media/java/WorkshopSentinel.jar.zbs")!=null;
                if(server) {
                string cfgDir=Path.Combine(dataDir,"WorkshopSentinel",name);
                At("Создание папки настроек",cfgDir);
                Directory.CreateDirectory(cfgDir);
                string cfg=Path.Combine(cfgDir,"WorkshopSentinel.properties");
                At("Создание начального конфига",cfg);
                if (!File.Exists(cfg)) using(var input=zip.GetEntry("WorkshopSentinel/config/WorkshopSentinel.properties").Open())
                    using(var output=new FileStream(cfg,FileMode.CreateNew)) input.CopyTo(output);
                }
                var settings=new Dictionary<string,string>();
                if(client) settings.Add(profile,clientText);
                if(server) settings.Add(ini,serverText);
                ApplySettings(settings,dataDir);
                DisableLegacyStaging(dataDir);
                At("Запись отчёта установки",Path.Combine(dataDir,"WorkshopSentinel-installation.txt"));
                File.WriteAllText(CurrentPath,"WorkshopSentinel установлен.\r\nJAR встроен в установщик, распакован и проверен по SHA-256.\r\nJAR: "+Path.Combine(dataDir,"mods","WorkshopSentinel","42","media","java","WorkshopSentinel.jar")+"\r\nПодпись ZombieBuddy: "+signed+"\r\nКлиент включён: "+client+"\r\nСервер настроен: "+(server?ini:"нет")+"\r\nКонфиг сохраняется; новая установка dry-run. Имя сервера определяется автоматически.\r\nДля серверной загрузки нужен уже установленный ZombieBuddy Java agent. Его запрос одобрения JAR подтверждается в ZombieBuddy.\r\n"+(signed?"Публичный ключ автора должен быть опубликован в Steam-профиле. Инструкция: mods/WorkshopSentinel/docs/SIGNING.md. Один раз выберите доверие автору в ZombieBuddy.\r\n":""),Encoding.UTF8);
        }
    }
    static void ValidatePayload(ZipArchive zip) {
        foreach(string file in new[]{"42/mod.info","42/media/java/WorkshopSentinel.jar","config/WorkshopSentinel.properties","client-support/WorkshopSentinelClient/42/mod.info","client-support/WorkshopSentinelClient/42/media/lua/client/WorkshopSentinelClientUI.lua","client-support/WorkshopSentinelClient/42/media/lua/shared/Translate/RU/UI.json"})
            if(zip.GetEntry("WorkshopSentinel/"+file)==null) throw new IOException("Установщик повреждён: нет "+file);
        using(var input=zip.GetEntry("WorkshopSentinel/42/media/java/WorkshopSentinel.jar").Open())
        using(var copy=new MemoryStream()) {
            input.CopyTo(copy); copy.Position=0;
            using(var jar=new ZipArchive(copy,ZipArchiveMode.Read))
                if(jar.GetEntry("io/workshopsentinel/Main.class")==null) throw new IOException("Повреждён встроенный JAR.");
        }
    }
    static void VerifyJar(ZipArchive zip,string path) {
        At("Проверка установленного JAR",path);
        using(var hash=SHA256.Create()) using(var input=zip.GetEntry("WorkshopSentinel/42/media/java/WorkshopSentinel.jar").Open()) using(var installed=File.OpenRead(path))
            if(BitConverter.ToString(hash.ComputeHash(input))!=BitConverter.ToString(hash.ComputeHash(installed))) throw new IOException("Установленный JAR не совпадает со встроенным.");
        var signature=zip.GetEntry("WorkshopSentinel/42/media/java/WorkshopSentinel.jar.zbs");
        if(signature!=null) {
            At("Проверка установленной подписи ZombieBuddy",path+".zbs");
            using(var hash=SHA256.Create()) using(var input=signature.Open()) using(var installed=File.OpenRead(path+".zbs"))
                if(BitConverter.ToString(hash.ComputeHash(input))!=BitConverter.ToString(hash.ComputeHash(installed))) throw new IOException("Подпись не совпадает со встроенной.");
        } else if(File.Exists(path+".zbs")) {
            // A sidecar from an older signed JAR must not invalidate an intentional unsigned build.
            string dataDir=Path.GetFullPath(Path.Combine(Path.GetDirectoryName(path),"..","..","..","..",".."));
            string saved=Path.Combine(dataDir,"WorkshopSentinel-installer-backups","obsolete-signature-"+Guid.NewGuid().ToString("N")+".zbs");
            Directory.CreateDirectory(Path.GetDirectoryName(saved)); File.Move(path+".zbs",saved);
        }
    }
    internal static string EnableServer(string text) {
        var matches=Regex.Matches(text,@"(?m)^Mods=([^\r\n]*)");
        if(matches.Count>1) throw new IOException("В server.ini несколько строк Mods; исправьте неоднозначную настройку.");
        string value=matches.Count==1?matches[0].Groups[1].Value:"";
        var ids=new List<string>(value.Split(new[]{';'},StringSplitOptions.RemoveEmptyEntries));
        foreach(string id in ids) if(id.Trim().TrimStart('\\')=="ServerAutoUpdate_B42") throw new IOException("Отключите ServerAutoUpdate_B42 в выбранном сервере: он конфликтует с WorkshopSentinel.");
        bool slash=value.Contains("\\");
        ids.RemoveAll(id=>id.Trim().TrimStart((char)92)=="WorkshopSentinelClient");
        foreach(string required in new[]{"ZombieBuddy","WorkshopSentinel"}) {
            bool found=false; foreach(string id in ids) if(id.Trim().TrimStart('\\')==required) found=true;
            if(!found) ids.Add((slash?"\\":"")+required);
        }
        string line="Mods="+String.Join(";",ids);
        return matches.Count==1?text.Remove(matches[0].Index,matches[0].Length).Insert(matches[0].Index,line):text+(text.EndsWith("\n")?"":"\r\n")+line+"\r\n";
    }
    internal static string EnableClient(string text) {
        var blocks=Regex.Matches(text,@"(?ms)^\s*mods\s*\{(?<body>[^{}]*)\}");
        if(blocks.Count!=1) throw new IOException("Не удалось прочитать профиль клиентских модов default.txt. Файл сохранён без изменений.");
        var block=blocks[0]; string body=block.Groups["body"].Value;
        if(Regex.IsMatch(body,@"(?m)^\s*mod\s*=\s*\\?ServerAutoUpdate_B42\s*,?\s*$")) throw new IOException("Отключите ServerAutoUpdate_B42 в клиентском профиле: он конфликтует с WorkshopSentinel.");
        string newline=text.Contains("\r\n")?"\r\n":"\n";
        int close=block.Index+block.Length-1;
        string addition="";
        foreach(string id in new[]{"WorkshopSentinel","WorkshopSentinelClient"})
            if(!Regex.IsMatch(body,@"(?m)^\s*mod\s*=\s*\\?"+Regex.Escape(id)+@"\s*,?\s*$")) addition+="    mod = "+id+","+newline;
        return text.Insert(close,addition);
    }
    static void ApplySettings(Dictionary<string,string> settings,string dataDir) {
        string backup=Path.Combine(dataDir,"WorkshopSentinel-installer-backups","settings-"+Guid.NewGuid().ToString("N"));
        var originals=new Dictionary<string,string>(); var changed=new List<string>();
        try {
            foreach(var setting in settings) {
                string path=setting.Key;
                if(File.Exists(path) && File.ReadAllText(path,Encoding.UTF8)==setting.Value) continue;
                Directory.CreateDirectory(backup);
                string saved=Path.Combine(backup,originals.Count+"-"+Path.GetFileName(path));
                if(File.Exists(path)) File.Copy(path,saved,false); else saved=null;
                originals.Add(path,saved);
                At("Автоматическое включение мода",path);
                Directory.CreateDirectory(Path.GetDirectoryName(path));
                string prepared=Path.Combine(backup,"prepared-"+originals.Count+".txt");
                // Preserve the source UTF-8 BOM policy and all untouched lines.
                bool bom=false; if(File.Exists(path)) { byte[] bytes=File.ReadAllBytes(path); bom=bytes.Length>=3 && bytes[0]==239 && bytes[1]==187 && bytes[2]==191; }
                File.WriteAllText(prepared,setting.Value,new UTF8Encoding(bom));
                WriteFile(prepared,path); changed.Add(path);
            }
        } catch(Exception original) {
            string failedOperation=Operation,failedPath=CurrentPath;
            var failures=new List<string>();
            for(int i=changed.Count-1;i>=0;i--) {
                string path=changed[i];
                try { if(originals[path]!=null) WriteFile(originals[path],path); else File.Delete(path); }
                catch(Exception rollback) { failures.Add(path+": "+rollback.Message); }
            }
            At(failedOperation,failedPath);
            if(failures.Count>0) throw new IOException(original.Message+"\nВосстановление настроек заблокировано. Резервная копия: "+backup+"\n"+String.Join("\n",failures),original);
            throw;
        }
    }
    static void DisableLegacyStaging(string dataDir) {
        string mods=Path.GetFullPath(Path.Combine(dataDir,"mods"));
        foreach(string candidate in Directory.GetDirectories(mods,".WorkshopSentinel-install-*")) {
            string full=Path.GetFullPath(candidate);
            if(!full.StartsWith(mods+Path.DirectorySeparatorChar,StringComparison.OrdinalIgnoreCase)) throw new IOException("Invalid legacy staging path");
            if((File.GetAttributes(full)&FileAttributes.ReparsePoint)!=0) continue;
            string info=Path.Combine(full,"42","mod.info");
            if(!File.Exists(info)) continue;
            if((File.GetAttributes(info)&FileAttributes.ReparsePoint)!=0 || (File.GetAttributes(Path.GetDirectoryName(info))&FileAttributes.ReparsePoint)!=0) continue;
            bool own=false;
            foreach(string line in File.ReadAllLines(info)) if(line.Trim()=="id=WorkshopSentinel") own=true;
            if(!own) continue;
            // Keep the old payload intact. Move only its mod.info out of discovery.
            string backup=Path.Combine(dataDir,"WorkshopSentinel-installer-backups","legacy-"+Guid.NewGuid().ToString("N"),Path.GetFileName(full),"42","mod.info");
            At("Отключение старой временной копии мода",info);
            Directory.CreateDirectory(Path.GetDirectoryName(backup));
            File.Move(info,backup);
        }
    }
    static void CheckOwned(string dataDir,string modId="WorkshopSentinel") {
        string target=Path.Combine(dataDir,"mods",modId);
        At("Проверка существующего мода",target);
        if (File.Exists(target)) throw new Exception("Путь занят файлом: "+target);
        if (!Directory.Exists(target)) return;
        string info=Path.Combine(target,"42","mod.info");
        bool own=false;
        if (File.Exists(info)) foreach(string line in File.ReadAllLines(info)) if (line.Trim()=="id="+modId) own=true;
        if (!own) throw new Exception("Не удалось подтвердить существующую установку WorkshopSentinel: "+target);
    }
    static void Deploy(ZipArchive zip,string prefix,string dataDir) {
        string mods=Path.Combine(dataDir,"mods");
        At("Создание папки mods",mods);
        Directory.CreateDirectory(mods);
        string modId=prefix.EndsWith("WorkshopSentinelClient/",StringComparison.Ordinal)?"WorkshopSentinelClient":"WorkshopSentinel";
        string target=Path.Combine(mods,modId);
        string staging=Path.Combine(dataDir,"WorkshopSentinel-installer-work","install-"+Guid.NewGuid().ToString("N"));
        At("Подготовка файлов",staging);
        Directory.CreateDirectory(staging);
        foreach(var entry in zip.Entries) {
            if (!entry.FullName.StartsWith(prefix,StringComparison.Ordinal) || entry.FullName.EndsWith("/")) continue;
            if(modId=="WorkshopSentinel" && entry.FullName.StartsWith(prefix+"client-support/",StringComparison.Ordinal)) continue;
            string rel=entry.FullName.Substring(prefix.Length).Replace('/',Path.DirectorySeparatorChar);
            string dest=Path.GetFullPath(Path.Combine(staging,rel));
            if (!dest.StartsWith(staging+Path.DirectorySeparatorChar,StringComparison.OrdinalIgnoreCase)) throw new Exception("Invalid payload path.");
            At("Распаковка файла",dest);
            Directory.CreateDirectory(Path.GetDirectoryName(dest));
            using(var input=entry.Open()) using(var output=new FileStream(dest,FileMode.CreateNew)) input.CopyTo(output);
        }
        string backup=null;
        if (Directory.Exists(target)) {
            // Backups stay outside mods, so PZ cannot discover a second copy of the same ID.
            string backupRoot=Path.Combine(dataDir,"WorkshopSentinel-installer-backups");
            Directory.CreateDirectory(backupRoot);
            backup=Path.Combine(backupRoot,DateTime.UtcNow.ToString("yyyyMMdd-HHmmss")+"-"+Guid.NewGuid().ToString("N"));
            // Do not rename the mod directory: PZ/Explorer may hold directory handles.
            foreach(string file in Directory.GetFiles(target,"*",SearchOption.AllDirectories)) {
                string copy=Path.Combine(backup,file.Substring(target.Length+1));
                At("Резервная копия файла",file);
                Directory.CreateDirectory(Path.GetDirectoryName(copy));
                File.Copy(file,copy,false);
            }
        }
        var changed=new List<string>();
        try {
            At("Подготовка папки мода",target);
            Directory.CreateDirectory(target);
            foreach(string file in Directory.GetFiles(staging,"*",SearchOption.AllDirectories)) {
                string dest=Path.Combine(target,file.Substring(staging.Length+1));
                At("Обновление файла",dest);
                Directory.CreateDirectory(Path.GetDirectoryName(dest));
                WriteFile(file,dest);
                changed.Add(dest);
            }
            if(modId=="WorkshopSentinel") {
                foreach(string rel in new[]{"42/media/lua/client/WorkshopSentinelClientModel.lua","42/media/lua/client/WorkshopSentinelClientUI.lua","42/media/lua/client/WorkshopSentinelMLOSCompat.lua","42/media/lua/shared/Translate/EN/UI.json","42/media/lua/shared/Translate/RU/UI.json"}) {
                    string obsolete=Path.GetFullPath(Path.Combine(target,rel.Replace('/',Path.DirectorySeparatorChar)));
                    if(!obsolete.StartsWith(target+Path.DirectorySeparatorChar,StringComparison.OrdinalIgnoreCase)) throw new IOException("Invalid migration path");
                    if(File.Exists(obsolete)) { At("Перенос устаревшего клиентского файла",obsolete); File.Delete(obsolete); changed.Add(obsolete); }
                }
            }
        } catch (Exception original) {
            string failedOperation=Operation,failedPath=CurrentPath;
            var failures=new List<string>();
            for(int i=changed.Count-1;i>=0;i--) {
                string dest=changed[i];
                try {
                    string copy=backup==null?null:Path.Combine(backup,dest.Substring(target.Length+1));
                    if(copy!=null && File.Exists(copy)) WriteFile(copy,dest);
                    else File.Delete(dest);
                } catch(Exception rollback) { failures.Add(dest+": "+rollback.Message); }
            }
            At(failedOperation,failedPath);
            if(failures.Count>0) throw new IOException(original.Message+"\nНе удалось полностью восстановить файлы. Резервная копия: "+backup+"\n"+String.Join("\n",failures),original);
            throw;
        }
        // Keep failed staging files for diagnostics; clean only our successful staging.
        try { Directory.Delete(staging,true); } catch { }
    }
    static void WriteFile(string source,string dest) {
        // Replace each file atomically, retaining the existing mod directory itself.
        string temp=dest+".ws-install-"+Guid.NewGuid().ToString("N");
        bool exists=File.Exists(dest);
        FileAttributes old=exists?File.GetAttributes(dest):FileAttributes.Normal;
        try {
            File.Copy(source,temp,false);
            File.SetAttributes(temp,FileAttributes.Normal);
            if(exists) {
                if((old & FileAttributes.ReadOnly)!=0) File.SetAttributes(dest,old & ~FileAttributes.ReadOnly);
                File.Replace(temp,dest,null);
                // Replacement succeeded; preserve non-readonly attributes of existing files.
            } else File.Move(temp,dest);
        } catch {
            if(exists && File.Exists(dest)) try { File.SetAttributes(dest,old); } catch { }
            throw;
        } finally { if(File.Exists(temp)) try { File.Delete(temp); } catch { } }
    }
    class SetupForm:Form {
        TextBox dataDir=new TextBox(); ComboBox name=new ComboBox();
        CheckBox client=new CheckBox(),server=new CheckBox();
        public SetupForm() {
            Text="WorkshopSentinel — установщик 0.4.12"; ClientSize=new Size(660,390);
            FormBorderStyle=FormBorderStyle.FixedDialog; MaximizeBox=false; StartPosition=FormStartPosition.CenterScreen;
            AddLabel("Один мод для клиента и dedicated server. Выберите папку данных Zomboid.",18,18,620);
            FolderRow(dataDir,DefaultDir,58);
            client.Text="Установить и включить для клиента"; client.Checked=true; client.SetBounds(18,100,400,25); Controls.Add(client);
            server.Text="Установить и настроить сервер"; server.SetBounds(18,132,400,25); Controls.Add(server);
            AddLabel("Сервер:",18,171,120); name.DropDownStyle=ComboBoxStyle.DropDownList; name.SetBounds(140,168,360,25); Controls.Add(name);
            dataDir.TextChanged+=(s,e)=>LoadServers(); server.CheckedChanged+=(s,e)=>{name.Enabled=server.Checked;}; LoadServers();
            AddLabel("JAR встроен: все файлы распаковываются и проверяются автоматически.\nКлиентский профиль и Mods сервера обновляются с резервной копией.\nИмя сервера определяется модом: параметры Java добавлять не нужно.\nСуществующий конфиг сохраняется; новый работает в dry-run.\nНа сервере нужен уже установленный ZombieBuddy Java agent.",18,215,620,105);
            var install=new Button(); install.Text="Установить"; install.SetBounds(480,335,155,35); Controls.Add(install);
            install.Click+=(s,e)=> { try {
                Install(dataDir.Text,name.Text,client.Checked,server.Checked);
                bool signed=File.Exists(Path.Combine(dataDir.Text,"mods","WorkshopSentinel","42","media","java","WorkshopSentinel.jar.zbs"));
                MessageBox.Show("Установка завершена. JAR распакован и проверен.\n"+(client.Checked?"Клиентский мод включён автоматически.\n":"")+(server.Checked?"Сервер "+name.Text+" настроен автоматически.\n":"")+"\nЗапустите игру или сервер заново. Если ZombieBuddy запросит одобрение JAR, подтвердите его в ZombieBuddy."+(signed?" Для подписанной сборки сначала настройте проверку ключа автора по docs/SIGNING.md.":"")+"\nОтчёт: "+Path.Combine(dataDir.Text,"WorkshopSentinel-installation.txt"),"WorkshopSentinel");
            } catch(Exception ex) { MessageBox.Show(ErrorDetails(ex),"Ошибка установки",MessageBoxButtons.OK,MessageBoxIcon.Error); } };
        }
        void LoadServers() {
            string previous=name.Text; name.Items.Clear();
            try { string dir=Path.Combine(dataDir.Text,"Server"); if(Directory.Exists(dir)) foreach(string file in Directory.GetFiles(dir,"*.ini")) name.Items.Add(Path.GetFileNameWithoutExtension(file)); } catch { }
            int preferred=-1;
            for(int i=0;i<name.Items.Count;i++)
                if(String.Equals((string)name.Items[i],"servertest",StringComparison.OrdinalIgnoreCase)) { preferred=i; break; }
            if(preferred>=0) name.SelectedIndex=preferred;
            else if(name.Items.Contains(previous)) name.SelectedItem=previous;
            else if(name.Items.Count>0) name.SelectedIndex=0;
            server.Enabled=name.Items.Count>0; server.Checked=server.Enabled; name.Enabled=server.Checked;
        }
        void AddLabel(string text,int x,int y,int width,int height=25) { var label=new Label();label.Text=text;label.SetBounds(x,y,width,height);Controls.Add(label); }
        void FolderRow(TextBox box,string value,int y) { box.Text=value;box.SetBounds(18,y,520,25);Controls.Add(box);var browse=new Button();browse.Text="Обзор…";browse.SetBounds(550,y-1,85,27);Controls.Add(browse);browse.Click+=(s,e)=>{using(var dialog=new FolderBrowserDialog()){dialog.Description="Папка данных Zomboid";if(Directory.Exists(box.Text))dialog.SelectedPath=box.Text;if(dialog.ShowDialog()==DialogResult.OK)box.Text=dialog.SelectedPath;}}; }
    }
}
