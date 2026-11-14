import { boot, register } from './core.js';
import * as home from './apps/home.js';
import * as map from './apps/map.js';
import * as sell from './apps/sell.js';
import * as buy from './apps/buy.js';
import * as auction from './apps/auction.js';
import * as wallet from './apps/wallet.js';
import * as mailbox from './apps/mailbox.js';
import * as prices from './apps/prices.js';
import * as admin from './apps/admin.js';
import * as about from './apps/about.js';

register('home', home);
register('map', map);
register('sell', sell);
register('buy', buy);
register('auction', auction);
register('wallet', wallet);
register('mailbox', mailbox);
register('prices', prices);
register('admin', admin);
register('about', about);

boot();
