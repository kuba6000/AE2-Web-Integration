# AE2 Web Integration

An add-on for AE2 that lets you view your terminal in your web browser wherever you are! This also includes viewing orders, cancelling them and even making a new ones!

# How?

This mod starts a web server on server boot that hosts a simple website that you can access directly, or through API calls

# Current features

- AE2 terminal (item list+sorting+filtering)
- Start a new order
- Check any CPU status
- Cancel any CPU order
- Order tracking

![image](https://github.com/user-attachments/assets/6a2a08a5-1938-4feb-97ea-6f787daedacd)
![image](https://github.com/user-attachments/assets/dc5e4f2f-ab6f-484f-9c13-dfff7a21cc11)

# Security

Currently a simple password protection is implemented (implemented based on [Basic access authentication](https://en.wikipedia.org/wiki/Basic_access_authentication))   
**Note: localhost connections are automatically authenticated by default, you can change that in config!**

# Requirements

- An open port if you want to access the service outside your local network (configurable, terminal will be hosted at http://your-server-ip-or-domain:configured-port/ for example: http://server.kuba6000.pl:2324/)
- Have your main network be the only one big AE2 system on the server (REQUIRED: minimum 5* crafting CPUs, *CONFIGURABLE) (Network that is displayed on the website is detected by the amount of crafting CPUs)
  
### **NOTE: At the moment there is no way to monitor more than 1 AE system at the time (it is only recommended to use the mod on your private dedicated server or single player!), MAYBE this will change in the future!**

# How to use

- Download the latest version for your game version from the releases page
- Drop the mod in your server mods folder (only on the server!) (This also works on single player, but is not recommended)
- Start the server
- You can now find the config in /configs/ae2webintegration.cfg. Edit the port number and password protection for your needs
- Reload the config (/ae2webintegration reload) or restart the server
- Make sure you have opened the configured port (firewall, redirections) if you want to use it on public internet
- Now you can visit http://your-server-ip-or-domain:configured-port/ and login popup should appear on your browser!
- Only password is verified, you can put anything in the username ;)

# Custom website

- If you already have a web server and want to host the panel there, you can!
- There is currently no API documentation...
- Check out [Simple proxy site](https://github.com/kuba6000/AE2-Web-Integration/tree/master/example_website) !
- There you can find a simple website written in PHP ready to use, it's just simple proxy to API calls to the AE2 endpoint.

# Compatibility

The mod is currently implemented only on 1.7.10 and 1.12.2 versions, although it might change in the future!   
A few remarks about compatibility:
- 1.7.10 version is based on [GTNH fork of AE2](https://github.com/GTNewHorizons/Applied-Energistics-2-Unofficial) and [GTNH fork AE2FC for 1.7.10](https://github.com/GTNewHorizons/AE2FluidCraft-Rework)
- 1.12.2 version is based on [AE2-UEL](https://github.com/AE2-UEL/Applied-Energistics-2) and [AE2FC for 1.12.2](https://github.com/AE2-UEL/AE2FluidCraft-Rework/)