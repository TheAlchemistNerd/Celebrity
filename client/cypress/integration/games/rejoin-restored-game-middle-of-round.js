import * as common from "../common-test-functions";

const index = Cypress.env('PLAYER_INDEX');
const g = 'got-it';
const p = 'pass';
const e = 'end-turn';

let doneCustomAction = false;

export const gameSpec = {
    index: index,

    restoredGame: true,
    playerNames: ['Marvin the Paranoid Android', 'Bender', 'Johnny 5', 'R2D2'],
    gameID: '1000',
    celebrityNames: [
        ['Neil Armstrong', 'Marilyn Monroe', 'John F. Kennedy', 'Audrey Hepburn', 'Paul McCartney', 'Rosa Parks'],
        ['Hippolyta', 'Alexander the Great', 'Hypatia', 'Xerxes', 'Helen of Troy', 'Plato'],
        ['Carl Friedrich Gauss', 'Leonhard Euler', 'John von Neumann', 'Kurt Gödel', 'Gottfried Leibniz', 'Joseph Fourier'],
        ['Emily Blunt', 'Emma Thompson', 'Judi Dench', 'Carey Mulligan', 'Cate Blanchett', 'Rhea Seehorn']
    ],

    turnIndexOffset: 11,

    // 24 total
    turns: [
        [g, g, p, g, p, g, g, e], // 5
        [g, g, g, g, e], // 9
        [p, g, g, g, g, g, g, e], // 15
        [g, g, g, p, e], // 18
        [e], //18
        [g, g, g, g, e], // 22
        [g, g], // 24

        [g, g, g, g, e], // 9
        [g, g, g, g, e], // 22
        [g, g, p, g, p, g, g, e], // 5
        [g, g, e], // 24
        [g, g, g, p, e], // 18
        [e], //18
        [p, g, g, g, g, g, g], // 15

        [g, g, p, g, p, g, g, e], // 5
        [e], //18
        [g, g, g, g, e], // 9
        [g, g, e], // 24
        [p, g, g, g, g, g, g, e], // 15
        [g, g, g, g, e], // 22
        [g, g, g], // 18
    ],

    customActions: [
        [function (clientState) {
            return !doneCustomAction && clientState.counter === 0 && clientState.index === 0;
        },
        function (clientState) {
            doneCustomAction = true;

            common.selectContextMenuItemForPlayer(clientState.playerName, '.playerInTeamTDClass', 'playerInTeamContextMenu', 'changePlayerInTeamToTeam1');

            // give time for it to process that Marvin has changed teams, otherwise it'll try to right-click on Johnny 5's old table cell (at index 1),
            // instead of the new one (at index 0) that appears after the teams table is re-rendered.
            cy.wait(500);
            common.selectContextMenuItemForPlayer(clientState.otherPlayers[1], '.playerInTeamTDClass', 'playerInTeamContextMenu', 'changePlayerInTeamToTeam1');
            common.selectContextMenuItemForPlayer(clientState.otherPlayers[0], '.playerInTeamTDClass', 'playerInTeamContextMenu', 'changePlayerInTeamToTeam0');

            // give time to re-render the table
            cy.wait(500);
            common.selectContextMenuItemForPlayer(clientState.otherPlayers[2], '.playerInTeamTDClass', 'playerInTeamContextMenu', 'changePlayerInTeamToTeam0');

            common.selectContextMenuItemForPlayer(clientState.otherPlayers[1], '.playerInTeamTDClass', 'playerInTeamContextMenu', 'moveUp');
            common.selectContextMenuItemForPlayer(clientState.otherPlayers[0], '.playerInTeamTDClass', 'playerInTeamContextMenu', 'moveDown');

            if (!clientState.fastMode) {
                cy.get('.playerInTeamTDClass').first().contains(clientState.otherPlayers[2]);
                cy.wait(2000); // give the others time to verify

                cy.contains('.teamlessPlayerLiClass', clientState.otherPlayers[1]);
                cy.wait(4000); // give the others time to verify
                common.selectContextMenuItemForPlayer(clientState.otherPlayers[1], '.playerInTeamTDClass', 'playerInTeamContextMenu', 'removePlayerInTeamFromGame')
                cy.get('[id="teamList"]').not(`:contains("${clientState.otherPlayers[1]}")`);
                cy.wait(4000); // give the others time to verify

                common.selectContextMenuItemForPlayer(clientState.otherPlayers[1], '.teamlessPlayerLiClass', 'contextMenuForTeamlessPlayer', 'changeToTeam1');
                common.selectContextMenuItemForPlayer(clientState.otherPlayers[1], '.playerInTeamTDClass', 'playerInTeamContextMenu', 'moveDown');
                cy.get(`.playerInTeamTDClass[teamindex="1"][playerindex="0"]:contains("${clientState.otherPlayers[1]}")`);
                cy.wait(4000); // give the others time to verify

                common.selectContextMenuItemForPlayer(clientState.otherPlayers[0], '.playerInTeamTDClass', 'playerInTeamContextMenu', 'makePlayerNextInTeam');
                cy.get('[id="gameStatusDiv"]').contains(`Waiting for ${clientState.otherPlayers[0]} to start turn`);
                cy.wait(4000); // give the others time to verify
                common.selectContextMenuItemForPlayer(clientState.otherPlayers[2], '.playerInTeamTDClass', 'playerInTeamContextMenu', 'makePlayerNextInTeam');
                cy.get('[id="gameStatusDiv"]').contains(`Waiting for ${clientState.otherPlayers[2]} to start turn`);
            }
            else {
                // give the others time to verify
                cy.wait(4000);
            }

            common.checkTeamList(clientState);

        }],

        [function (clientState) {
            return !doneCustomAction && clientState.counter === 0 && clientState.index === 1;
        },
        function (clientState) {
            doneCustomAction = true;

            if (!clientState.fastMode) {
                cy.get(`.playerInTeamTDClass[teamindex="0"][playerindex="0"]:contains("${clientState.otherPlayers[2]}")`, { timeout: 10000 });

                cy.contains('.teamlessPlayerLiClass', clientState.otherPlayers[1], { timeout: 10000 });
                cy.get('[id="teamList"]').not(`:contains("${clientState.otherPlayers[1]}")`, { timeout: 10000 });
                cy.get(`.playerInTeamTDClass[teamindex="1"][playerindex="0"]:contains("${clientState.otherPlayers[1]}")`, { timeout: 10000 });

                cy.get('[id="gameStatusDiv"]').contains('It\'s your turn!', { timeout: 10000 });
                cy.get('[id="gameStatusDiv"]').contains(`Waiting for ${clientState.otherPlayers[2]} to start turn`, { timeout: 10000 });
            }
            else {
                cy.get(`.playerInTeamTDClass[teamindex="0"][playerindex="1"]:contains("${clientState.playerName}")`, { timeout: 10000 });
            }

            common.checkTeamList(clientState);
        }],

        [function (clientState) {
            return !doneCustomAction && clientState.counter === 0 && clientState.index === 2;
        },
        function (clientState) {
            doneCustomAction = true;

            if (!clientState.fastMode) {
                cy.get(`.playerInTeamTDClass[teamindex="0"][playerindex="0"]:contains("${clientState.otherPlayers[2]}")`, { timeout: 10000 });
                // refresh the page
                cy.visit('http://192.168.1.17:8080/celebrity.html');
                common.joinGame(clientState.playerName, clientState.gameID, clientState.hostName);
                cy.get('[id="teamList"]').not(`:contains("${clientState.playerName}")`, { timeout: 10000 });
                cy.get(`.playerInTeamTDClass[teamindex="1"][playerindex="0"]:contains("${clientState.playerName}")`, { timeout: 10000 });

                cy.get('[id="gameStatusDiv"]').contains(`Waiting for ${clientState.otherPlayers[1]} to start turn`, { timeout: 10000 });
                cy.get('[id="gameStatusDiv"]').contains(`Waiting for ${clientState.otherPlayers[2]} to start turn`, { timeout: 10000 });
            }
            else {
                cy.get(`.playerInTeamTDClass[teamindex="0"][playerindex="1"]:contains("${clientState.otherPlayers[1]}")`, { timeout: 10000 });
            }

            common.checkTeamList(clientState);
        }],

        [function (clientState) {
            return !doneCustomAction && clientState.counter === 0 && clientState.index === 3;
        },
        function (clientState) {
            doneCustomAction = true;

            if (!clientState.fastMode) {
                cy.get(`.playerInTeamTDClass[teamindex="0"][playerindex="0"]:contains("${clientState.playerName}")`, { timeout: 10000 });
                cy.contains('.teamlessPlayerLiClass', clientState.otherPlayers[2], { timeout: 10000 });
                cy.get('[id="teamList"]').not(`:contains("${clientState.otherPlayers[2]}")`, { timeout: 10000 });
                cy.get(`.playerInTeamTDClass[teamindex="1"][playerindex="0"]:contains("${clientState.otherPlayers[2]}")`, { timeout: 10000 });

                cy.get('[id="gameStatusDiv"]').contains(`Waiting for ${clientState.otherPlayers[1]} to start turn`, { timeout: 10000 });
                cy.get('[id="gameStatusDiv"]').contains('It\'s your turn!', { timeout: 10000 });
            }
            else {
                cy.get(`.playerInTeamTDClass[teamindex="0"][playerindex="1"]:contains("${clientState.otherPlayers[1]}")`, { timeout: 10000 });
            }

            common.checkTeamList(clientState);
        }],
    ],
}

// common.openSiteAndPlayGame(gameSpec);