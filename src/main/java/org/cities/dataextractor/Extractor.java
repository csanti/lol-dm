package org.cities.dataextractor;

import org.glassfish.jersey.jackson.JacksonFeature;
import org.json.JSONArray;
import org.json.JSONObject;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.ListIterator;
import java.util.concurrent.TimeUnit;


public class Extractor {

    private static final String BASE_URL = "https://euw1.api.riotgames.com/api/lol/euw/";
    private static final String API_KEY = "6ab6acff-85f6-4580-b127-32067e5667ab";
    private static int requests = 0;

    public static void main(String[] args) {
        if(args[0].equals("summoners")) {
            try {
                if(args.length != 4) {
                    System.out.println("Comando incorrecto: summoners {nº paginas} {primera pagina} {out filename}");
                    return;
                }
                extractSummoners(Integer.parseInt(args[1]), Integer.parseInt(args[2]), args[3]);
            } catch(Exception e) {
                System.out.println("Error: "+e.toString());
            }
        }
        else if(args[0].equals("matches")) {
            try {
                if(args.length != 3) {
                    System.out.println("Comando incorrecto: matches {in filename} {out filename}");
                    return;
                }
                extractMatches(args[1], args[2]);
            } catch (Exception e) {
                System.out.println("Error: "+e.toString());
            }

        }
        else if(args[0].equals("matches_stats")) {
            try {
                if(args.length != 3) {
                    System.out.println("Comando incorrecto: matches_stats {in filename} {out filename}");
                    return;
                }
                extractMatchesStats(args[1], args[2]);
            } catch (Exception e) {
                System.out.println("Error: "+e.toString());
            }
        }
        else if(args[0].equals("champions")) {
            try {
                if(args.length != 2) {
                    System.out.println("Comando incorrecto: champions {out filename}");
                    return;
                }
                extractChampionWinRates(args[1]);
            } catch (Exception e) {
                System.out.println("Error: "+e.toString());
            }
        }
        else {
            System.out.println("Orden no reconocida");
        }

    }

    public static void extractSummoners(int num, int firstPage, String outFileName) {
        System.out.println("Extrayendo lista de summoners:");

        Client client = ClientBuilder.newBuilder().register(JacksonFeature.class).build();

        JSONArray summonersInfo = new JSONArray();

        for(int i = firstPage; i < firstPage+num; i++) {
            // Peticiones a un endpint de lolking en el que se pueden pedir listas de summoners.
            // Por cada petición (página) devuelve 10 summoners
            JSONObject lolkingresponse = executeGetRequest("http://www.lolking.net/leaderboards/68b9efb3d8cfe899a4e1c17b58e76ec8/euw/"+i+".json");
            JSONArray summoners = lolkingresponse.getJSONArray("data");

            for(int j = 0; j < summoners.length(); j++) {
                // El endpoint de lolking solo devuelve summonerid, hacemos petición a riot para entontrar accountid
                JSONObject accountInfo  = executeGetRequest("https://euw1.api.riotgames.com/lol/summoner/v3/summoners/"+summoners.getJSONObject(j).getLong("summoner_id")+"?api_key="+API_KEY);
                summonersInfo.put(accountInfo);
            }
        }
        System.out.println("Guardando json con "+summonersInfo.length()+" summoners");
        PrintWriter writer = null;
        try {
            writer = new PrintWriter(outFileName);
            writer.print(summonersInfo);
        } catch(Exception e) {
            System.out.println(e.toString());
        } finally {
            writer.close();
        }
    }

    public static void extractMatches(String summonersFileName, String outFileName) {

        JSONArray matches = new JSONArray();
        String json_string = "";
        try {
            List<String> lines = Files.readAllLines(Paths.get(summonersFileName), Charset.forName("ISO-8859-1"));
            ListIterator<String> lines_iterator = lines.listIterator();
            while (lines_iterator.hasNext()) {
                json_string += lines_iterator.next();
            }
        } catch (IOException e) {
            System.out.println("Error: "+e.toString());
        }

        JSONArray summonersInfo = new JSONArray(json_string);

        for(int i = 0; i < summonersInfo.length(); i++) {
            long accountId = summonersInfo.getJSONObject(i).getLong("accountId");
            String accountName = summonersInfo.getJSONObject(i).getString("name");
            System.out.println("Obteniendo partidas de summoner con accountid: "+ accountId + " - Name: "+accountName);
            JSONObject matchList = executeGetRequest("https://euw1.api.riotgames.com/lol/match/v3/matchlists/by-account/"+accountId+"/recent?&api_key="+API_KEY);
            JSONArray summonerMatches = matchList.getJSONArray("matches");

            int summonerAddedMatches = 0;
            for(int j = 0; j < summonerMatches.length(); j++) {
                int queue = summonerMatches.getJSONObject(j).getInt("queue");
                if(queue == 410 || queue == 420 || queue == 440) {
                    matches.put(summonerMatches.get(j));
                    summonerAddedMatches++;
                }
            }
            System.out.println("    "+summonerAddedMatches+" partidas extraidas de "+accountName);
        }

        System.out.println("Guardando json con "+matches.length()+" partidas");
        PrintWriter writer = null;
        try {
            writer = new PrintWriter(outFileName);
            writer.print(matches);
        } catch(Exception e) {
            System.out.println(e.toString());
        } finally {
            writer.close();
        }
    }

    public static void  extractMatchesStats(String inputFileName, String outFileName) {
        JSONObject matchesObject = new JSONObject();
        JSONArray matchesArray = new JSONArray();
        String json_string = "";
        try {
            List<String> lines = Files.readAllLines(Paths.get(inputFileName), Charset.forName("UTF-8"));
            ListIterator<String> lines_iterator = lines.listIterator();
            while(lines_iterator.hasNext()) {
                json_string += lines_iterator.next();
            }
        } catch (IOException e) {
            System.out.println(e.toString());
            return;
        }
        JSONArray jsonArray = new JSONArray(json_string);

        for(int i = 0; i<jsonArray.length(); i++) {
            Object matchId = jsonArray.getJSONObject(i).get("gameId");
            JSONObject matchObject = executeGetRequest("https://euw1.api.riotgames.com/api/lol/euw/v2.2/match/"+matchId.toString()+"?api_key="+API_KEY);
            if(matchObject == null) // devolverá null si hay un error o timeout en la conexión
                continue;
            matchesArray.put(matchObject);
            if(i%10 == 0 && i != 0) {
                System.out.println("Tamaño de matches: "+ matchesArray.length());
            }
        }
        matchesObject.put("matches", matchesArray);
        System.out.println("Guardando json con "+matchesArray.length()+" matches");
        PrintWriter writer = null;
        try {
            writer = new PrintWriter(outFileName);
            writer.print(matchesObject);
        } catch(Exception e) {
            System.out.println(e.toString());
        } finally {
            writer.close();
        }

    }

    public static void extractChampionWinRates(String outFileName) {
        JSONObject championWrJson = new JSONObject();

        JSONObject championGGResponse = executeGetRequest("http://api.champion.gg/v2/champions?api_key=361848ab661179653f9cbfe3a17412e6");
        System.out.println(championGGResponse.toString());
    }

    public static JSONObject executeGetRequest( String url){
        try{
            /*
            requests += 1;
            if( requests % 500 == 0 ){
                TimeUnit.MINUTES.sleep(10);
            }*/
            TimeUnit.SECONDS.sleep(1);
            URL mh_url = new URL( url );
            System.out.println( url );
            HttpURLConnection con = (HttpURLConnection) mh_url.openConnection();
            con.setConnectTimeout(5000); // si tarda mas de 5 segundos lanza SocketTimeoutException
            int responseCode = con.getResponseCode();
            if( responseCode != 200 ){
                /*
                if(responseCode == 404) {
                    System.err.println("Response Code " + responseCode + " received.");
                    return null;
                }
                */

                System.err.println( "Response Code " + responseCode + " received. Re issuing request." );
                TimeUnit.SECONDS.sleep( 10 );
                return executeGetRequest( url);
            }
            BufferedReader in = new BufferedReader( new InputStreamReader( con.getInputStream() ) );
            String inputLine;
            StringBuffer response = new StringBuffer();

            while ((inputLine = in.readLine()) != null) {
                response.append(inputLine);
            }
            in.close();

            return new JSONObject( response.toString() );

        }catch( Exception e ){
            System.err.println( e );
            return null;
        }
    }

}