CREATE TABLE lb_questactions
(
    questactionid INT UNSIGNED NOT NULL AUTO_INCREMENT,
    questid       INT          NOT NULL DEFAULT '0',
    status        INT          NOT NULL DEFAULT '0',
    data          BLOB         NOT NULL,
    PRIMARY KEY (questactionid)
);

CREATE TABLE lb_questprogress
(
    id            INT UNSIGNED                                               NOT NULL AUTO_INCREMENT,
    characterid   INT                                                        NOT NULL,
    queststatusid INT UNSIGNED                                               NOT NULL DEFAULT '0',
    progressid    INT                                                        NOT NULL DEFAULT '0',
    progress      VARCHAR(15) CHARACTER SET latin1 COLLATE latin1_german1_ci NOT NULL DEFAULT '',
    PRIMARY KEY (id)
);

CREATE TABLE lb_questrequirements
(
    questrequirementid INT UNSIGNED NOT NULL AUTO_INCREMENT,
    questid            INT          NOT NULL DEFAULT '0',
    status             INT          NOT NULL DEFAULT '0',
    data               BLOB         NOT NULL,
    PRIMARY KEY (questrequirementid)
);

CREATE TABLE lb_queststatus
(
    queststatusid INT UNSIGNED NOT NULL AUTO_INCREMENT,
    characterid   INT          NOT NULL DEFAULT '0',
    quest         INT          NOT NULL DEFAULT '0',
    status        INT          NOT NULL DEFAULT '0',
    time          INT          NOT NULL DEFAULT '0',
    expires       BIGINT       NOT NULL DEFAULT '0',
    forfeited     INT          NOT NULL DEFAULT '0',
    completed     INT          NOT NULL DEFAULT '0',
    info          TINYINT      NOT NULL DEFAULT '0',
    PRIMARY KEY (queststatusid)
);