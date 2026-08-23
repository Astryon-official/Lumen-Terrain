$src = @"
using System;
using System.Runtime.InteropServices;
using System.Text;
public static class Ocl {
  [DllImport("OpenCL.dll", CharSet=CharSet.Ansi)]
  public static extern int clGetPlatformIDs(uint n, IntPtr[] p, out uint c);
  [DllImport("OpenCL.dll", CharSet=CharSet.Ansi)]
  public static extern int clGetPlatformInfo(IntPtr p, uint k, UIntPtr sz, StringBuilder v, out UIntPtr r);
  [DllImport("OpenCL.dll", CharSet=CharSet.Ansi)]
  public static extern int clGetDeviceIDs(IntPtr p, ulong t, uint n, IntPtr[] d, out uint c);
  [DllImport("OpenCL.dll", CharSet=CharSet.Ansi)]
  public static extern int clGetDeviceInfo(IntPtr d, uint k, UIntPtr sz, StringBuilder v, out UIntPtr r);
  [DllImport("OpenCL.dll", CharSet=CharSet.Ansi)]
  public static extern int clGetDeviceInfo(IntPtr d, uint k, UIntPtr sz, out uint v, out UIntPtr r);
  [DllImport("OpenCL.dll", CharSet=CharSet.Ansi)]
  public static extern int clGetDeviceInfo(IntPtr d, uint k, UIntPtr sz, out ulong v, out UIntPtr r);
  [DllImport("OpenCL.dll", CharSet=CharSet.Ansi)]
  public static extern IntPtr clCreateContext(IntPtr[] props, uint n, IntPtr[] d, IntPtr f, IntPtr u, out int e);
}
"@
Add-Type -TypeDefinition $src
$c=[uint32]0
$null=[Ocl]::clGetPlatformIDs(0,$null,[ref]$c)
$p=New-Object 'IntPtr[]' $c
$null=[Ocl]::clGetPlatformIDs($c,$p,[ref]$c)
$sb=New-Object System.Text.StringBuilder 512
$size=New-Object UIntPtr ([uint64]512)
$nret=[UIntPtr]::Zero
for($i=0;$i -lt $c;$i++){
  $null=$sb.Clear()
  $null=[Ocl]::clGetPlatformInfo($p[$i],0x0902,$size,$sb,[ref]$nret)
  "== platform[$i]: $($sb.ToString())"
  $dc=[uint32]0
  $buf=New-Object 'IntPtr[]' 8
  $rd=[Ocl]::clGetDeviceIDs($p[$i],4,8,$buf,[ref]$dc)
  "   all-devices rc=$rd count=$dc"
  for($d=0;$d -lt [Math]::Min($dc,8);$d++){
    $dev=$buf[$d]
    $t32=[uint32]0
    $null=[Ocl]::clGetDeviceInfo($dev,0x1004,$size,[ref]$t32,[ref]$nret)
    "   dev[$d] ptr=0x$($dev.ToString('X')) type=0x$($t32.ToString('X'))"
    $av=[uint32]0
    $ra=[Ocl]::clGetDeviceInfo($dev,0x1027,$size,[ref]$av,[ref]$nret)
    "   available rc=$ra val=$av"
    $null=$sb.Clear()
    $rn=[Ocl]::clGetDeviceInfo($dev,0x1028,$size,$sb,[ref]$nret)
    "   name rc=$rn '$($sb.ToString())'"
    # try context creation
    $ec=0
    $ctx=[Ocl]::clCreateContext($null,1,@($dev),[IntPtr]::Zero,[IntPtr]::Zero,[ref]$ec)
    "   ctx rc=$ec handle=0x$($ctx.ToString('X'))"
  }
}
