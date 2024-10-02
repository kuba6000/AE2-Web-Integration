# AE2 Web Integration - 1.7.10 minecraft addon

### Host your AE2 terminal on web to quickly check on your storage/orders when not in game!  
### THIS MOD IS ONLY FOR PRIVATE SERVERS, SUPPORTS ONLY ONE AE SYSTEM AND SERVER-SIDED ONLY
### THIS MOD IS COMPATIBLE ONLY WITH [GTNH FORK OF AE2](https://github.com/GTNewHorizons/Applied-Energistics-2-Unofficial)

## Current features:
  - AE2 terminal (item list+sorting+filtering)
  - Start a new order
  - Check any CPU status
  - Cancel any CPU order
  - Order tracking
  - Password protection on the website, disable on localhost connections by default (can change in config) (implemented using authoarization header, described here: https://en.wikipedia.org/wiki/Basic_access_authentication)

![image](https://github.com/user-attachments/assets/6a2a08a5-1938-4feb-97ea-6f787daedacd)
![image](https://github.com/user-attachments/assets/dc5e4f2f-ab6f-484f-9c13-dfff7a21cc11)

## Requirements:
  - An open port (configurable, terminal will be hosted at http://your-server-ip-or-domain:configured-port/ for example: http://server.kuba6000.pl:2324/)
  - Have your main network be the only one big AE2 system on the server (REQUIRED: minimum 5* crafting CPUs, *CONFIGURABLE) (Network that is displayed on the website is detected by the amount of crafting CPUs)

## How to use:
  - Download the latest version from releases page
  - Drop the mod in your server mods folder (only on the server!)
  - Start the server
  - You can now find the config in /configs/ae2webintegration.cfg. Edit the port number and password protection for your needs
  - Reload the config (/ae2webintegration reload) or restart the server
  - Make sure you have opened the configured port (firewall, redirections) if you want to use it on public internet
  - Now you can visit http://your-server-ip-or-domain:configured-port/ and login popup should appear on your browser!
  - Only password is verified, you can put anything in the username ;)

## Custom website
  - If you already have a web server and want to host the panel there, you can!
  - Check out https://github.com/kuba6000/AE2-Web-Integration/tree/master/example_website !
  - There you can find a simple website written in PHP ready to use, it's just simple proxy to API calls to the AE2 endpoint.
