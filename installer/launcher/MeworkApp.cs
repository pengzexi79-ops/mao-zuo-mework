using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using System.Web.Script.Serialization;
using System.Windows.Forms;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;

internal static class Program
{
    internal const string Title = "\u732b\u4f5c\u00b7Mework";
    private static Mutex instanceMutex;
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] private static extern IntPtr FindWindow(string c, string n);
    [DllImport("user32.dll")] private static extern bool SetForegroundWindow(IntPtr h);
    [DllImport("user32.dll")] private static extern bool ShowWindow(IntPtr h, int c);

    [STAThread]
    private static void Main()
    {
        bool created;
        instanceMutex = new Mutex(true, "Local\\MeworkDesktop-4F2BA8E5-6552-4A84-8D87-69BDE6B21B79", out created);
        if (!created)
        {
            IntPtr existing = FindWindow(null, Title);
            if (existing != IntPtr.Zero) { ShowWindow(existing, 9); SetForegroundWindow(existing); }
            return;
        }
        Application.EnableVisualStyles();
        Application.SetCompatibleTextRenderingDefault(false);
        Application.Run(new MainWindow());
        GC.KeepAlive(instanceMutex);
    }
}

internal sealed class MainWindow : Form
{
    private readonly Panel loadingPanel;
    private readonly Label statusLabel;
    private readonly WebView2 webView;
    private int port;

    internal MainWindow()
    {
        Text = Program.Title;
        StartPosition = FormStartPosition.CenterScreen;
        Width = 1440; Height = 900; MinimumSize = new Size(1100, 720);
        WindowState = FormWindowState.Maximized; BackColor = Color.White;
        try { Icon = Icon.ExtractAssociatedIcon(Application.ExecutablePath); } catch { }

        webView = new WebView2();
        webView.Dock = DockStyle.Fill; webView.Visible = false; Controls.Add(webView);
        loadingPanel = new Panel(); loadingPanel.Dock = DockStyle.Fill; loadingPanel.BackColor = Color.White;
        Controls.Add(loadingPanel); loadingPanel.BringToFront();

        Label title = new Label(); title.AutoSize = true;
        title.Font = new Font("Microsoft YaHei UI", 20F, FontStyle.Bold); title.Text = Program.Title;
        loadingPanel.Controls.Add(title);
        statusLabel = new Label(); statusLabel.AutoSize = false; statusLabel.TextAlign = ContentAlignment.MiddleCenter;
        statusLabel.Font = new Font("Microsoft YaHei UI", 10F); statusLabel.Text = "\u6b63\u5728\u542f\u52a8\u672c\u673a\u670d\u52a1...";
        loadingPanel.Controls.Add(statusLabel);
        ProgressBar progress = new ProgressBar(); progress.Style = ProgressBarStyle.Marquee;
        progress.MarqueeAnimationSpeed = 24; loadingPanel.Controls.Add(progress);
        loadingPanel.Resize += delegate
        {
            int x = loadingPanel.ClientSize.Width / 2, y = loadingPanel.ClientSize.Height / 2;
            title.Location = new Point(x - title.Width / 2, y - 70);
            statusLabel.SetBounds(x - 240, y - 20, 480, 32); progress.SetBounds(x - 180, y + 24, 360, 8);
        };
        Shown += OnShown;
    }

    private async void OnShown(object sender, EventArgs e)
    {
        string appDir = Path.GetFullPath(AppDomain.CurrentDomain.BaseDirectory).TrimEnd('\\');
        try
        {
            port = await Task.Run(delegate { return RuntimeHost.EnsureRunning(appDir); });
            statusLabel.Text = "\u6b63\u5728\u52a0\u8f7d\u732b\u4f5c\u5de5\u4f5c\u53f0...";
            string profileDir = Path.Combine(appDir, "data", "desktop-webview");
            Directory.CreateDirectory(profileDir);
            CoreWebView2Environment environment = await CoreWebView2Environment.CreateAsync(null, profileDir);
            await webView.EnsureCoreWebView2Async(environment);
            webView.CoreWebView2.Settings.IsStatusBarEnabled = false;
            webView.CoreWebView2.Settings.AreDevToolsEnabled = false;
            webView.CoreWebView2.Settings.AreBrowserAcceleratorKeysEnabled = false;
            webView.CoreWebView2.NewWindowRequested += OnNewWindowRequested;
            webView.Source = new Uri("http://127.0.0.1:" + port + "/");
            loadingPanel.Visible = false; webView.Visible = true; webView.Focus();
        }
        catch (Exception ex)
        {
            RuntimeHost.Log(appDir, "Desktop startup failed: " + ex);
            MessageBox.Show(this,
                "\u732b\u4f5c\u542f\u52a8\u5931\u8d25\u3002\r\n\r\n" + ex.Message + "\r\n\r\n\u8bf7\u67e5\u770b data\\logs\\desktop-launcher.log",
                Program.Title, MessageBoxButtons.OK, MessageBoxIcon.Error);
            Close();
        }
    }

    private void OnNewWindowRequested(object sender, CoreWebView2NewWindowRequestedEventArgs e)
    {
        string localPrefix = "http://127.0.0.1:" + port + "/";
        if (e.Uri.StartsWith(localPrefix, StringComparison.OrdinalIgnoreCase)) webView.CoreWebView2.Navigate(e.Uri);
        else
        {
            ProcessStartInfo info = new ProcessStartInfo(e.Uri); info.UseShellExecute = true; Process.Start(info);
        }
        e.Handled = true;
    }
}

internal static class RuntimeHost
{
    private const int DefaultPort = 8760;

    internal static int EnsureRunning(string appDir)
    {
        EnsureEnvironment(appDir);
        string envPath = Path.Combine(appDir, ".env");
        int port = ReadPort(envPath);
        if (OwnServiceHealthy(port, appDir)) return port;
        if (PortIsListening(port))
        {
            port = FindFreePort(DefaultPort, 200);
            PersistPort(envPath, port);
            Log(appDir, "Port conflict detected; selected " + port + ".");
        }
        StartBatch(Path.Combine(appDir, "start.bat"), appDir, false, true, 0);
        for (int attempt = 0; attempt < 360; attempt++)
        {
            if (OwnServiceHealthy(port, appDir))
            {
                Log(appDir, "Backend healthy on port " + port + ".");
                return port;
            }
            Thread.Sleep(500);
        }
        throw new InvalidOperationException("The local service did not become ready within three minutes.");
    }

    private static void EnsureEnvironment(string appDir)
    {
        string envPath = Path.Combine(appDir, ".env");
        if (File.Exists(envPath)) return;
        int port = FindFreePort(DefaultPort, 200);
        int exitCode = StartBatch(Path.Combine(appDir, "ensure_env.bat"), appDir, true, false, port);
        if (exitCode != 0 || !File.Exists(envPath))
            throw new InvalidOperationException("Could not create the private local configuration.");
    }

    private static int StartBatch(string path, string appDir, bool wait, bool skipBrowser, int preferredPort)
    {
        if (!File.Exists(path)) throw new FileNotFoundException("Required startup file is missing.", path);
        ProcessStartInfo info = new ProcessStartInfo();
        info.FileName = Environment.GetEnvironmentVariable("ComSpec") ?? "cmd.exe";
        info.Arguments = "/d /c call \"" + path + "\"";
        info.WorkingDirectory = appDir;
        info.UseShellExecute = false; info.CreateNoWindow = true; info.WindowStyle = ProcessWindowStyle.Hidden;
        if (skipBrowser) info.EnvironmentVariables["APP_SKIP_BROWSER"] = "true";
        if (preferredPort > 0) info.EnvironmentVariables["APP_PORT"] = preferredPort.ToString();
        Process process = Process.Start(info);
        if (process == null) throw new InvalidOperationException("Windows could not create the local service process.");
        if (!wait) return 0;
        if (!process.WaitForExit(60000))
        {
            try { process.Kill(); } catch { }
            throw new InvalidOperationException("Local configuration timed out.");
        }
        return process.ExitCode;
    }

    private static bool OwnServiceHealthy(int port, string appDir)
    {
        try
        {
            HttpWebRequest request = (HttpWebRequest)WebRequest.Create("http://127.0.0.1:" + port + "/api/system/env");
            request.Method = "GET"; request.Proxy = null; request.Timeout = 1800; request.ReadWriteTimeout = 1800;
            string json;
            using (HttpWebResponse response = (HttpWebResponse)request.GetResponse())
            using (Stream stream = response.GetResponseStream())
            using (StreamReader reader = new StreamReader(stream, Encoding.UTF8))
            {
                if (response.StatusCode != HttpStatusCode.OK) return false;
                json = reader.ReadToEnd();
            }
            JavaScriptSerializer serializer = new JavaScriptSerializer();
            Dictionary<string, object> envelope = serializer.Deserialize<Dictionary<string, object>>(json);
            object dataValue;
            Dictionary<string, object> data = null;
            if (envelope != null && envelope.TryGetValue("data", out dataValue))
                data = dataValue as Dictionary<string, object>;
            return data != null &&
                (PathBelongsToApp(data, "outputDir", appDir) ||
                 PathBelongsToApp(data, "materialsDir", appDir) ||
                 PathBelongsToApp(data, "localPython", appDir) ||
                 PathBelongsToApp(data, "portablePython", appDir));
        }
        catch { return false; }
    }

    private static bool PathBelongsToApp(Dictionary<string, object> data, string key, string appDir)
    {
        object value;
        if (!data.TryGetValue(key, out value) || value == null) return false;
        try
        {
            string root = Path.GetFullPath(appDir).TrimEnd('\\') + "\\";
            string candidate = Path.GetFullPath(Convert.ToString(value)).TrimEnd('\\') + "\\";
            return candidate.StartsWith(root, StringComparison.OrdinalIgnoreCase);
        }
        catch { return false; }
    }

    private static int ReadPort(string envPath)
    {
        foreach (string raw in File.ReadAllLines(envPath, Encoding.UTF8))
        {
            string line = raw.Trim();
            if (!line.StartsWith("PORT=", StringComparison.OrdinalIgnoreCase)) continue;
            int port;
            if (Int32.TryParse(line.Substring(5).Trim(), out port) && port > 0 && port <= 65535) return port;
        }
        return DefaultPort;
    }

    private static void PersistPort(string envPath, int port)
    {
        List<string> lines = new List<string>(File.ReadAllLines(envPath, Encoding.UTF8));
        bool replaced = false;
        for (int index = 0; index < lines.Count; index++)
        {
            if (!lines[index].TrimStart().StartsWith("PORT=", StringComparison.OrdinalIgnoreCase)) continue;
            lines[index] = "PORT=" + port; replaced = true; break;
        }
        if (!replaced) lines.Add("PORT=" + port);
        File.WriteAllLines(envPath, lines.ToArray(), new UTF8Encoding(false));
    }

    private static int FindFreePort(int preferred, int range)
    {
        for (int port = preferred; port < preferred + range; port++)
        {
            if (PortIsListening(port)) continue;
            TcpListener listener = null;
            try
            {
                listener = new TcpListener(IPAddress.Loopback, port);
                listener.Server.ExclusiveAddressUse = true; listener.Start(); return port;
            }
            catch (SocketException) { }
            finally { if (listener != null) try { listener.Stop(); } catch { } }
        }
        throw new InvalidOperationException("No free local application port is available.");
    }

    private static bool PortIsListening(int port)
    {
        try
        {
            foreach (IPEndPoint endpoint in IPGlobalProperties.GetIPGlobalProperties().GetActiveTcpListeners())
                if (endpoint.Port == port) return true;
        }
        catch { }
        return false;
    }

    internal static void Log(string appDir, string message)
    {
        try
        {
            string path = Path.Combine(appDir, "data", "logs", "desktop-launcher.log");
            Directory.CreateDirectory(Path.GetDirectoryName(path));
            File.AppendAllText(path, DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss") + " " + message + Environment.NewLine, Encoding.UTF8);
        }
        catch { }
    }
}
