# AE2 Web Integration - 1.7.10 minecraft addon

### Host your AE2 terminal on web to quickly check on your storage/orders when not in game!  
### THIS MOD IS ONLY FOR PRIVATE SERVERS, SUPPORTS ONLY ONE AE SYSTEM AND SERVER-SIDED ONLY

## Current features:
  - AE2 terminal (item list)
  - Start a new order
  - Check any CPU status
  - Cancel any CPU order
  - Password protection on the website

![image](https://github.com/user-attachments/assets/5757e707-1139-434e-99bb-eca6e4e787ff)

## Requirements:
  - An open port (configurable, terminal will be hosted at http://your-server-ip-or-domain:configured-port/ for example: http://server.kuba6000.pl:2324/)
  - Have only one big AE2 system on the server (REQUIRED: more than 5 crafting CPUs)

## How to use:
  - Download the latest version from releases page
  - Drop the mod in your server mods folder (only on the server!)
  - Start the server
  - You can now find the config in /configs/ae2webintegration.cfg. Edit the port number and password protection for your needs
  - Reload the config (/ae2webintegration reload) or restart the server
  - Make sure you have opened the configured port (firewall, redirections) if you want to use it on public internet
  - Now you can visit http://your-server-ip-or-domain:configured-port/ and login popup should appear on your browser!
  - Only password is verified, you can put anything in the username ;)
