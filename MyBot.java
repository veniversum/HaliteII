import hlt.*;

import java.util.*;

public class MyBot {
    private final Networking networking;
    private final GameMap gameMap;
    private final HashMap<Integer, Planet> planetMap;
    private final int selfId;
    private int turnCount = 0;
    private Strategy strategy = Strategy.START;

    private Position targetCorner;
    private int cornerShipId = -1;

    public MyBot() {
        networking = new Networking();
        gameMap = networking.initialize("ValueNetwork");
        planetMap = new HashMap<>();
        selfId = gameMap.getMyPlayerId();

        // We now have 1 full minute to analyse the initial map.
        final String initialMapIntelligence =
                "width: " + gameMap.getWidth() +
                        "; height: " + gameMap.getHeight() +
                        "; players: " + gameMap.getAllPlayers().size() +
                        "; planets: " + gameMap.getAllPlanets().size();
        Log.log(initialMapIntelligence);

        gameMap.getAllPlanets().values().forEach(planet -> planetMap.put(planet.getId(), planet));
        Position pos = gameMap.getMyPlayer().getShips().values().iterator().next();
        double cornerX = pos.getXPos() < gameMap.getWidth() / 2 ? 0 : gameMap.getWidth();
        double cornerY = pos.getYPos() < gameMap.getHeight() / 2 ? 0 : gameMap.getHeight();
        targetCorner = new Position(cornerX, cornerY);
    }

    static double scoreTarget(Ship ship, Planet planet) {
        return ship.getDistanceTo(planet);
    }

    public static void main(String[] args) {
        MyBot bot = new MyBot();
        while (true) {
            bot.networking.updateMap(bot.gameMap);
            bot.turnCount++;
            bot.play();
        }
    }

    boolean dockable(Planet planet) {
        return planet.isOwned();
    }

    public void play() {
        final ArrayList<Move> moveList = new ArrayList<>();
        Queue<Ship> movable_ships = new LinkedList<>(gameMap.getMyPlayer().getShips().values());
        if (turnCount == 30 && gameMap.getAllPlayers().size() > 2) {
            strategy = Strategy.EXPAND;
        }
        while (!movable_ships.isEmpty()) {
            Ship ship = movable_ships.poll();
            if (ship.getDockingStatus() != Ship.DockingStatus.Undocked) {
                continue;
            }

            switch (strategy) {
                case EXPAND:
                    Ship cornerShip = gameMap.getShip(selfId, cornerShipId);
                    if (cornerShip == null) {
                        Log.log(ship + " is corner ship");
                        cornerShipId = ship.getId();
                    }
                    if (ship.getId() == cornerShipId) {
                        moveList.add(Navigation.navigateShipTowardsTarget(gameMap, ship, targetCorner, Constants.MAX_SPEED, true, Constants.MAX_NAVIGATION_CORRECTIONS, Math.PI / 180.0));
                        break;
                    }
                case START:
                    Optional<Planet> planet_target = gameMap.getAllPlanets().values().stream()
                            .filter(p -> !p.isOwned() || (p.getOwner() == selfId && !p.willBeFull()))
                            .min(Comparator.comparingDouble(ship::getDistanceTo));
                    Optional<Ship> ship_target = gameMap.getAllShips().stream()
                            .filter(s -> s.getOwner() != selfId && (ship.getId() % 2 == 0 || s.getDockingStatus() != Ship.DockingStatus.Undocked))
                            .min(Comparator.comparingDouble(ship::getDistanceTo));

                    Optional<Double> planet_target_dist = planet_target.map(ship::getDistanceTo);
                    Optional<Double> ship_target_dist = ship_target.map(ship::getDistanceTo);

                    if (ship_target_dist.isPresent()) {
                        if (planet_target_dist.isPresent() && ((ship.getId() + 1 % 6 != 5 && planet_target_dist.get() <= ship_target_dist.get()))) {
                            // Planet is target
                            Planet planet = planet_target.get();
                            Log.log("Ship " + ship + " going to planet " + planet);
                            planet_target.get().addShipTarget();
                            if (ship.canDock(planet)) {
                                moveList.add(new DockMove(ship, planet));
                            } else {
                                moveList.add(Navigation.navigateShipToDock(gameMap, ship, planet, Constants.MAX_SPEED));
                            }
                        } else {
                            assert ship_target.isPresent();
                            Log.log("Ship " + ship + " going to enemy ship " + ship_target.get());
                            moveList.add(Navigation.navigateShipTowardsTarget(gameMap, ship, ship.getClosestPoint(ship_target.get()), Constants.MAX_SPEED, true, Constants.MAX_NAVIGATION_CORRECTIONS, Math.PI / 180.0));
                        }
                    } else if (planet_target.isPresent()) {
                        Log.log("Ship " + ship + " going to planet " + planet_target.get());
                        Planet planet = planet_target.get();
                        if (ship.canDock(planet)) {
                            moveList.add(new DockMove(ship, planet));
                        } else {
                            moveList.add(Navigation.navigateShipToDock(gameMap, ship, planet_target.get(), Constants.MAX_SPEED));
                        }
                    }
            }
        }
        moveList.removeIf(Objects::isNull);
        Networking.sendMoves(moveList);
    }

    enum Strategy {START, EXPAND, ATTACK}
}
