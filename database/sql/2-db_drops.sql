#THIS SQL MUST BE USED AFTER 'db_database.sql'
#NEXT SQL 'db_shopupdate.sql' IS OPTIONAL

USE `cosmic`;

  CREATE TABLE IF NOT EXISTS `drop_data` (
    `id` bigint(20) NOT NULL AUTO_INCREMENT,
    `dropperid` int(11) NOT NULL,
    `itemid` int(11) NOT NULL DEFAULT '0',
    `minimum_quantity` int(11) NOT NULL DEFAULT '1',
    `maximum_quantity` int(11) NOT NULL DEFAULT '1',
    `questid` int(11) NOT NULL DEFAULT '0',
    `chance` int(11) NOT NULL DEFAULT '0',
    PRIMARY KEY (`id`),
    UNIQUE KEY (`dropperid`, `itemid`),
    KEY `mobid` (`dropperid`),
    INDEX (dropperid, itemid)
  ) ENGINE=MyISAM  DEFAULT CHARSET=latin1 AUTO_INCREMENT=1 ;

  #pass (sorted) data from one table to another
  INSERT INTO drop_data (dropperid, itemid, minimum_quantity, maximum_quantity, questid, chance)
  (
    SELECT dropperid, itemid, minimum_quantity, maximum_quantity, questid, chance
    FROM temp_data
  );
  DROP TABLE temp_data;